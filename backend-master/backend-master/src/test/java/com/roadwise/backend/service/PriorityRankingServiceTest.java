package com.roadwise.backend.service;

import com.roadwise.backend.model.CityRoad;
import com.roadwise.backend.model.RoadReport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LexSort: tier assignment, footprint arithmetic, and the lexicographic order.
 *
 * <p>These are the rules a budget hearing turns on, so each branch of the tier matrix
 * is asserted individually rather than through a single aggregate case.</p>
 */
class PriorityRankingServiceTest {

    private final PriorityRankingService ranking = new PriorityRankingService();

    private static RoadReport report(Integer severity, String roadImportance,
                                     Double length, Double width, Double depth) {
        RoadReport report = new RoadReport();
        report.setCvSeverityScore(severity);
        report.setRoadImportance(roadImportance);
        report.setDamageLength(length);
        report.setDamageWidth(width);
        report.setDamageDepth(depth);
        return report;
    }

    // ------------------------------------------------------------- tier matrix

    @Test
    void severityThreeIsHighRegardlessOfArterialStatus() {
        assertEquals(PriorityRankingService.TIER_HIGH, ranking.tierFor(3, false));
        assertEquals(PriorityRankingService.TIER_HIGH, ranking.tierFor(3, true));
    }

    @Test
    void severityTwoEscalatesOnlyOnCoreArterials() {
        assertEquals(PriorityRankingService.TIER_HIGH, ranking.tierFor(2, true));
        assertEquals(PriorityRankingService.TIER_MEDIUM, ranking.tierFor(2, false));
    }

    @Test
    void severityOneIsMediumOnlyOnCoreArterials() {
        assertEquals(PriorityRankingService.TIER_MEDIUM, ranking.tierFor(1, true));
        assertEquals(PriorityRankingService.TIER_LOW, ranking.tierFor(1, false));
    }

    @Test
    void unassessedSeverityRanksLowRatherThanHigh() {
        // Null must never be treated as "worst" — an un-inferred report has no evidence
        // of severity, and promoting it would invert the queue.
        assertEquals(PriorityRankingService.TIER_LOW, ranking.tierFor(null, false));
    }

    @Test
    void outOfRangeSeverityIsClamped() {
        assertEquals(PriorityRankingService.TIER_LOW, ranking.tierFor(0, false));
        assertEquals(PriorityRankingService.TIER_HIGH, ranking.tierFor(9, false));
    }

    @Test
    void badgesMatchSpecificationColours() {
        assertEquals("#dc3545", ranking.badgeFor(PriorityRankingService.TIER_HIGH));
        assertEquals("#ff8c00", ranking.badgeFor(PriorityRankingService.TIER_MEDIUM));
        assertEquals("#28a745", ranking.badgeFor(PriorityRankingService.TIER_LOW));
    }

    // --------------------------------------------------------------- footprint

    @Test
    void footprintIsLengthTimesWidthTimesDepthInCm() {
        assertEquals(24.0, ranking.areaScoreFor(report(3, "Core", 2.0, 3.0, 4.0)), 1e-9);
    }

    @Test
    void missingOrZeroDepthFallsBackToOneCentimetre() {
        assertEquals(6.0, ranking.areaScoreFor(report(3, "Core", 2.0, 3.0, null)), 1e-9);
        assertEquals(6.0, ranking.areaScoreFor(report(3, "Core", 2.0, 3.0, 0.0)), 1e-9);
    }

    @Test
    void negativeOrMissingDimensionsContributeNothing() {
        assertEquals(0.0, ranking.areaScoreFor(report(3, "Core", null, null, null)), 1e-9);
        assertEquals(0.0, ranking.areaScoreFor(report(3, "Core", -2.0, 3.0, 4.0)), 1e-9);
    }

    // ------------------------------------------------------- lexicographic order

    @Test
    void tierOutranksFootprint() {
        RoadReport lowTierHugeFootprint = report(1, "Non-Core", 100.0, 100.0, 100.0);
        RoadReport highTierTinyFootprint = report(3, "Non-Core", 0.1, 0.1, 0.1);

        List<RoadReport> ordered = ranking.rank(List.of(lowTierHugeFootprint, highTierTinyFootprint));

        assertEquals(highTierTinyFootprint, ordered.get(0),
                "a high-tier report must precede a low-tier one no matter how large the low-tier footprint");
    }

    @Test
    void footprintBreaksTiesWithinATier() {
        RoadReport small = report(3, "Non-Core", 1.0, 1.0, 1.0);
        RoadReport large = report(3, "Non-Core", 5.0, 5.0, 5.0);

        List<RoadReport> ordered = ranking.rank(List.of(small, large));

        assertEquals(large, ordered.get(0));
        assertEquals(small, ordered.get(1));
    }

    @Test
    void midTierSitsBetweenTheOtherTwo() {
        RoadReport high = report(3, "Non-Core", 1.0, 1.0, 1.0);
        RoadReport medium = report(2, "Non-Core", 99.0, 99.0, 99.0);
        RoadReport low = report(1, "Non-Core", 99.0, 99.0, 99.0);

        List<RoadReport> ordered = ranking.rank(List.of(low, medium, high));

        assertEquals(List.of(high, medium, low), ordered);
    }

    @Test
    void sortIsStableForIdenticalKeys() {
        RoadReport first = report(3, "Non-Core", 1.0, 1.0, 1.0);
        RoadReport second = report(3, "Non-Core", 1.0, 1.0, 1.0);

        List<RoadReport> ordered = ranking.rank(List.of(first, second));

        assertEquals(List.of(first, second), ordered);
    }

    @Test
    void emptyInputYieldsEmptyOutput() {
        assertTrue(ranking.rank(List.of()).isEmpty());
        assertTrue(ranking.rank(null).isEmpty());
    }

    // ---------------------------------------------------- authoritative road flag

    @Test
    void authoritativeRoadFlagOverridesTheReportSnapshot() {
        // The report says Non-Core, but the road inventory says otherwise. The
        // inventory wins: the snapshot may predate the road's reclassification.
        RoadReport report = report(2, "Non-Core", 1.0, 1.0, 1.0);

        CityRoad road = new CityRoad();
        road.setIsCoreArterial(true);

        ranking.applyScores(report, road);

        assertEquals(PriorityRankingService.TIER_HIGH, report.getTierScore());
    }

    @Test
    void snapshotIsUsedWhenTheRoadRecordIsAbsent() {
        RoadReport report = report(2, "Core", 1.0, 1.0, 1.0);

        ranking.applyScores(report);

        assertEquals(PriorityRankingService.TIER_HIGH, report.getTierScore());
        assertEquals(1.0, report.getAreaScore(), 1e-9);
    }
}
