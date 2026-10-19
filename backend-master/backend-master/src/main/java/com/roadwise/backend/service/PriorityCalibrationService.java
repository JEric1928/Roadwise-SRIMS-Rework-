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
 * <p>Weights live on the unit simplex ({@code Σθ̂ = 1}, {@code θ̂ᵢ ∈ [0, 1]}) via an
 * exact Euclidean projection, so they stay an interpretable convex combination no
 * matter how many audits accumulate. The reported score is rescaled onto the
 * historical {@code [0, 4]} domain by {@link #MAX_PRIORITY_SCORE}: with no audits a
 * uniform simplex of {@code 0.25} reproduces the legacy default weight of {@code 1.0}
 * exactly, which is why persisted V20 rows need no migration.</p>
 */
@Service
public class PriorityCalibrationService {

    /** severity, coreArterial, area, depth. */
    public static final int FEATURE_COUNT = 4;

    /**
     * Upper bound of the reported analytic priority index.
     *
     * <p>Weights are constrained to the unit simplex, so {@code Σ θ̂ᵢ φ̃ᵢ ∈ [0, 1]}.
     * Multiplying by this constant returns the score to the historical {@code [0, 4]}
     * domain that persisted {@code priority_calibration} rows and already-quoted budget
     * figures assume — meaning the change is behaviour-preserving for the default case
     * and requires no data migration.</p>
     */
    public static final double MAX_PRIORITY_SCORE = 4.0;

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
        // Projected on read, so a row written before the simplex constraint existed
        // scores identically to a calibrated one without a write on the read path.
        double[] theta = SimplexProjection.project(new double[] {
                nz(row.getWeightSeverity(), 1.0),
                nz(row.getWeightCoreArterial(), 1.0),
                nz(row.getWeightArea(), 1.0),
                nz(row.getWeightDepth(), 1.0)
        });
        return new Weights(theta[0], theta[1], theta[2], theta[3],
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

        double[] previous = SimplexProjection.project(new double[] {
                nz(row.getWeightSeverity(), 1.0),
                nz(row.getWeightCoreArterial(), 1.0),
                nz(row.getWeightArea(), 1.0),
                nz(row.getWeightDepth(), 1.0)
        });
        RecursiveLeastSquares rls = RecursiveLeastSquares.restore(
                FEATURE_COUNT, RecursiveLeastSquares.DEFAULT_FORGETTING_FACTOR,
                previous, row.getCovarianceJson());

        double[] regressors = featureVector(
                observation.severityFeature(), observation.coreArterial(),
                observation.areaScore(), observation.depthCm());

        double[] updated;
        try {
            // Regressors are normalized to [0, 1] and the weights are constrained to
            // the unit simplex, so the linear model spans [0, 1]. Fitting directly
            // against an observed priority on the reported [0, 4] scale would be
            // structurally un-fittable — the residuals would never close and the
            // weights would sit wherever the projection happened to pin them. Fit in
            // the scaled domain and scale back on output instead.
            updated = rls.update(regressors, normalizedTarget(observation.observedPriority()));
        } catch (IllegalArgumentException invalidObservation) {
            // Non-finite input must not corrupt a persisted calibration.
            System.err.println(">>> [RLS] rejected audit: " + invalidObservation.getMessage());
            return currentWeights();
        }

        double[] theta = SimplexProjection.project(updated);

        row.setWeightSeverity(theta[0]);
        row.setWeightCoreArterial(theta[1]);
        row.setWeightArea(theta[2]);
        row.setWeightDepth(theta[3]);
        row.setCovarianceJson(rls.covarianceCsv());
        row.setAuditCount((row.getAuditCount() == null ? 0 : row.getAuditCount()) + 1);
        repository.save(row);

        return new Weights(theta[0], theta[1], theta[2], theta[3], row.getAuditCount());
    }

    /**
     * Continuous analytic priority in {@code [0, ~4]}: the weighted sum of normalized
     * severity, arterial status, footprint, and depth.
     */
    public double priorityScore(RoadReport report, Weights weights) {
        if (report == null) {
            return 0.0;
        }
        double[] features = featureVector(
                normalizedSeverity(report.getCvSeverityScore()),
                report.isCoreArterialSnapshot(),
                report.getAreaScore() == null ? 0.0 : report.getAreaScore(),
                report.getDamageDepth() == null ? 0.0 : report.getDamageDepth());
        // Re-projected even for a caller-supplied record, so the simplex invariant holds
        // on every path rather than only on the service's own persisted rows.
        double[] theta = weights == null
                ? SimplexProjection.project(new double[] {1.0, 1.0, 1.0, 1.0})
                : SimplexProjection.project(new double[] {
                        weights.severity(), weights.coreArterial(),
                        weights.area(), weights.depth()});
        double weighted = 0.0;
        for (int i = 0; i < FEATURE_COUNT; i++) {
            weighted += theta[i] * features[i];
        }
        return MAX_PRIORITY_SCORE * weighted;
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

    /**
     * Observed priority on the reported {@code [0, 4]} scale, clamped and rescaled to
     * the {@code [0, 1]} domain the simplex-constrained model lives in.
     *
     * <p>Non-finite input is passed through untouched so {@link RecursiveLeastSquares#update}
     * rejects it — the existing fail-closed path — instead of being silently trained as
     * zero.</p>
     */
    private static double normalizedTarget(double observedPriority) {
        if (!Double.isFinite(observedPriority)) {
            return observedPriority;
        }
        double clamped = Math.max(0.0, Math.min(MAX_PRIORITY_SCORE, observedPriority));
        return clamped / MAX_PRIORITY_SCORE;
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
