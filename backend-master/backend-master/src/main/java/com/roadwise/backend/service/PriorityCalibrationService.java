package com.roadwise.backend.service;

import com.roadwise.backend.model.PriorityCalibration;
import com.roadwise.backend.model.RoadReport;
import com.roadwise.backend.repository.PriorityCalibrationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adaptive priority weighting by discrete recursive least squares (Module 4).
 *
 * <p>Four weights cover the four signals the ranking already reasons about: severity,
 * core-arterial status, volumetric footprint, and surveyed depth. Each logged CPDO
 * field audit is one observation, and folding it in costs a 4x4 update — no
 * re-inference, no batch job.</p>
 *
 * <p>The weights feed {@link #priorityScore}, a continuous index reported alongside
 * the ranking for budget triage <em>within</em> a tier. They deliberately do not
 * reorder the lexicographic queue; see {@link PriorityRankingService} for why.</p>
 *
 * <p>With the default weights of 1.0 and no audits recorded, {@code priorityScore} is
 * a plain sum of normalized features, so a fresh deployment reproduces a static,
 * explainable ranking and calibration only begins to matter once evidence exists.</p>
 */
@Service
public class PriorityCalibrationService {

    /** severity, coreArterial, area, depth. */
    public static final int FEATURE_COUNT = 4;

    /**
     * Feature normalizers. Footprint is compressed logarithmically because raw
     * {@code m²·cm} values span several orders of magnitude across a city; depth is
     * scaled linearly against a realistic surface-course thickness.
     */
    private static final double REFERENCE_AREA = 100_000.0;

    private static final double REFERENCE_DEPTH_CM = 30.0;

    private final PriorityCalibrationRepository repository;

    public PriorityCalibrationService(PriorityCalibrationRepository repository) {
        this.repository = repository;
    }

    /** The calibrated weights as read-only data. */
    public record Weights(double severity, double coreArterial, double area, double depth, int auditCount) {
    }

    /**
     * One historical outcome used to calibrate: the normalized features a report
     * presented, and the priority the CPDO concluded it actually deserved.
     */
    public record AuditObservation(double severityFeature,
                                   boolean coreArterial,
                                   double areaScore,
                                   double depthCm,
                                   double observedPriority) {
    }

    /** Current weights; falls back to the static 1.0 defaults before any audit. */
    @Transactional(readOnly = true)
    public Weights currentWeights() {
        PriorityCalibration row = load();
        return new Weights(
                nz(row.getWeightSeverity(), 1.0),
                nz(row.getWeightCoreArterial(), 1.0),
                nz(row.getWeightArea(), 1.0),
                nz(row.getWeightDepth(), 1.0),
                row.getAuditCount() == null ? 0 : row.getAuditCount());
    }

    /**
     * Fold one field audit into the calibration and persist the new state.
     *
     * <p>{@code synchronized} because RLS is a stateful read-modify-write of a single
     * row: two concurrent audits reading the same covariance would otherwise both
     * apply to the same prior and one update would be lost.</p>
     *
     * @return the updated weights
     */
    @Transactional
    public synchronized Weights recordAudit(AuditObservation observation) {
        if (observation == null) {
            throw new IllegalArgumentException("observation is required");
        }
        PriorityCalibration row = load();

        double[] previous = {
                nz(row.getWeightSeverity(), 1.0),
                nz(row.getWeightCoreArterial(), 1.0),
                nz(row.getWeightArea(), 1.0),
                nz(row.getWeightDepth(), 1.0)
        };
        RecursiveLeastSquares rls = RecursiveLeastSquares.restore(
                FEATURE_COUNT, RecursiveLeastSquares.DEFAULT_FORGETTING_FACTOR,
                previous, row.getCovarianceJson());

        double[] regressors = featureVector(
                observation.severityFeature(), observation.coreArterial(),
                observation.areaScore(), observation.depthCm());

        double[] updated;
        try {
            updated = rls.update(regressors, observation.observedPriority());
        } catch (IllegalArgumentException invalidObservation) {
            // Non-finite input must not corrupt a persisted calibration.
            System.err.println(">>> [RLS] rejected audit: " + invalidObservation.getMessage());
            return currentWeights();
        }

        row.setWeightSeverity(updated[0]);
        row.setWeightCoreArterial(updated[1]);
        row.setWeightArea(updated[2]);
        row.setWeightDepth(updated[3]);
        row.setCovarianceJson(rls.covarianceCsv());
        row.setAuditCount((row.getAuditCount() == null ? 0 : row.getAuditCount()) + 1);
        repository.save(row);

        return new Weights(updated[0], updated[1], updated[2], updated[3], row.getAuditCount());
    }

    /**
     * Continuous analytic priority in {@code [0, ~4]}: the weighted sum of normalized
     * severity, arterial status, footprint, and depth.
     */
    public double priorityScore(RoadReport report, Weights weights) {
        if (report == null) {
            return 0.0;
        }
        Weights effective = weights == null ? new Weights(1.0, 1.0, 1.0, 1.0, 0) : weights;
        double[] features = featureVector(
                normalizedSeverity(report.getCvSeverityScore()),
                report.isCoreArterialSnapshot(),
                report.getAreaScore() == null ? 0.0 : report.getAreaScore(),
                report.getDamageDepth() == null ? 0.0 : report.getDamageDepth());
        double score = 0.0;
        double[] w = {effective.severity(), effective.coreArterial(), effective.area(), effective.depth()};
        for (int i = 0; i < FEATURE_COUNT; i++) {
            score += w[i] * features[i];
        }
        return score;
    }

    /**
     * Build the regressor vector: every feature normalized into {@code [0, 1]} so a
     * single set of weights is meaningful across all four signals.
     */
    public double[] featureVector(double severityFeature, boolean coreArterial,
                                  double areaScore, double depthCm) {
        return new double[]{
                clamp01(severityFeature),
                coreArterial ? 1.0 : 0.0,
                clamp01(Math.log1p(Math.max(0.0, areaScore)) / Math.log1p(REFERENCE_AREA)),
                clamp01(Math.max(0.0, depthCm) / REFERENCE_DEPTH_CM)
        };
    }

    private static double normalizedSeverity(Integer severity) {
        if (severity == null || severity < 1) {
            return 0.0;
        }
        return Math.min(3, severity) / 3.0;
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double nz(Double value, double fallback) {
        return value == null || !Double.isFinite(value) ? fallback : value;
    }

    /**
     * The singleton row, created on demand.
     *
     * <p>V20 inserts it, so this only fires against a schema restored from a partial
     * backup. Creating it here keeps ranking available instead of failing the request
     * on a missing row.</p>
     */
    private PriorityCalibration load() {
        return repository.findById(PriorityCalibration.SINGLETON_ID)
                .orElseGet(() -> repository.save(new PriorityCalibration()));
    }
}
