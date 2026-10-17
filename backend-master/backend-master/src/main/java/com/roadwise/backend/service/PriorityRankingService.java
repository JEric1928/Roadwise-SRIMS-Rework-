package com.roadwise.backend.service;

import com.roadwise.backend.model.CityRoad;
import com.roadwise.backend.model.RoadReport;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Deterministic two-level lexicographic priority ranking ("LexSort", Module 4).
 *
 * <p>Ordering is a strict two-key lexicographic comparison:</p>
 * <ol>
 *   <li><b>Tier</b> descending — 3 HIGH, 2 MEDIUM, 1 LOW, derived from the
 *       <em>aggregated multi-image</em> severity plus core-arterial status.</li>
 *   <li><b>Footprint</b> descending — {@code length(m) × width(m) × depth(cm)}, the
 *       volumetric tie-breaker inside a tier.</li>
 * </ol>
 *
 * <p>Two deliberate departures from the specification's sample code, both because
 * the sample does not compile or would mis-rank:</p>
 *
 * <ul>
 *   <li>{@code Comparator.reversedOrder()} is not a JDK method. The tie-breaker is
 *       written as {@code comparingDouble(...).reversed()}. {@code reverseOrder()}
 *       would also work on a boxed {@code Double}, but the unboxed comparator avoids
 *       an allocation per comparison.</li>
 *   <li>The sample guards the depth term with {@code depth > 0 ? depth : 1.0}, which
 *       silently mixes units: a report with no surveyed depth would otherwise
 *       contribute 1.0 <em>metres</em> of thickness and outrank genuinely deep
 *       potholes. Here the fallback is explicit, named, and in the same unit as the
 *       input ({@value #DEFAULT_DEPTH_CM} cm).</li>
 * </ul>
 *
 * <p>Ordering is intentionally <em>not</em> adjusted by the adaptive weights in
 * {@link PriorityCalibrationService}: a ranking an LGU has to defend in a budget
 * hearing cannot shift because a regression moved a coefficient. Calibration feeds a
 * separate, reported analytic score; sorting stays categorical and volumetric.</p>
 */
@Service
public class PriorityRankingService {

    public static final int TIER_HIGH = 3;
    public static final int TIER_MEDIUM = 2;
    public static final int TIER_LOW = 1;

    /** Bootstrap badge colours (the values named in the specification). */
    public static final String BADGE_HIGH = "#dc3545";
    public static final String BADGE_MEDIUM = "#ff8c00";
    public static final String BADGE_LOW = "#28a745";

    /**
     * Thickness assumed when a survey recorded no depth, in centimetres — the same
     * unit as {@code damage_depth}. Matches the specification's 1.0 default, made
     * explicit rather than implied.
     */
    private static final double DEFAULT_DEPTH_CM = 1.0;

    /** Unassessed severity ranks at the bottom, never at the top. */
    private static final int MIN_SEVERITY = 1;
    private static final int MAX_SEVERITY = 3;

    /**
     * Tier from the aggregated severity and the road's arterial status.
     *
     * <p>HIGH: any severity-3 damage, or severity-2 on a core arterial.
     * MEDIUM: severity-2 on a non-arterial, or severity-1 on a core arterial.
     * LOW: everything else.</p>
     */
    public int tierFor(Integer severity, boolean coreArterial) {
        int effective = severity == null ? MIN_SEVERITY : clampSeverity(severity);
        if (effective == 3 || (effective == 2 && coreArterial)) {
            return TIER_HIGH;
        }
        if ((effective == 2) || (effective == 1 && coreArterial)) {
            return TIER_MEDIUM;
        }
        return TIER_LOW;
    }

    /** Colour used by the dashboard badges for a tier. */
    public String badgeFor(int tier) {
        return switch (tier) {
            case TIER_HIGH -> BADGE_HIGH;
            case TIER_MEDIUM -> BADGE_MEDIUM;
            default -> BADGE_LOW;
        };
    }

    /**
     * Volumetric footprint in {@code m²·cm}. The mixed unit is the specification's
     * formula kept verbatim; because the scale factor is uniform it cannot change the
     * ordering, and keeping it verbatim means stored values stay comparable with any
     * historical ranking.
     */
    public double areaScoreFor(RoadReport report) {
        double length = positive(report.getDamageLength());
        double width = positive(report.getDamageWidth());
        Double rawDepth = report.getDamageDepth();
        double depth = rawDepth != null && rawDepth > 0.0 ? rawDepth : DEFAULT_DEPTH_CM;
        return length * width * depth;
    }

    /**
     * Recompute {@code tierScore} and {@code areaScore} on the entity.
     *
     * @param authoritativeRoad the linked road record when it has been loaded; its
     *        {@code is_core_arterial} flag wins over the report's denormalised
     *        snapshot, which may predate the road's classification.
     */
    public RoadReport applyScores(RoadReport report, CityRoad authoritativeRoad) {
        boolean coreArterial = authoritativeRoad != null
                ? authoritativeRoad.qualifiesAsCoreArterial()
                : report.isCoreArterialSnapshot();
        int tier = tierFor(report.getCvSeverityScore(), coreArterial);
        report.setTierScore(tier);
        report.setAreaScore(areaScoreFor(report));
        return report;
    }

    /** Convenience overload for callers that only have the report. */
    public RoadReport applyScores(RoadReport report) {
        return applyScores(report, null);
    }

    /**
     * Score every report, then return them ordered by (tier desc, footprint desc).
     *
     * <p>A stable sort, so two reports with identical keys retain their incoming order
     * (which callers pass as newest-first) rather than shuffling between renders.</p>
     */
    public List<RoadReport> rank(List<RoadReport> reports) {
        if (reports == null || reports.isEmpty()) {
            return new ArrayList<>();
        }
        for (RoadReport report : reports) {
            applyScores(report);
        }
        List<RoadReport> ordered = new ArrayList<>(reports);
        ordered.sort(LEX_SORT);
        return ordered;
    }

    /** (tier desc, footprint desc) — the two-level lexicographic order. */
    public static final Comparator<RoadReport> LEX_SORT =
            Comparator.comparingInt(PriorityRankingService::tierOf)
                    .reversed()
                    .thenComparing(Comparator.comparingDouble(PriorityRankingService::footprintOf).reversed());

    private static int tierOf(RoadReport report) {
        Integer tier = report.getTierScore();
        return tier == null ? TIER_LOW : tier;
    }

    private static double footprintOf(RoadReport report) {
        Double area = report.getAreaScore();
        return area == null ? 0.0 : area;
    }

    private static double positive(Double value) {
        return value == null || value < 0.0 ? 0.0 : value;
    }

    private static int clampSeverity(int severity) {
        return Math.max(MIN_SEVERITY, Math.min(MAX_SEVERITY, severity));
    }
}
