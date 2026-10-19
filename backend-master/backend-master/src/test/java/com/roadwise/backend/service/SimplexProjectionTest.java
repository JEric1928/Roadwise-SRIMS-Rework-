package com.roadwise.backend.service;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Euclidean projection onto the probability simplex (Duchi et al., 2008).
 *
 * <p>Four properties pin the implementation: the result is always a distribution,
 * it is idempotent, a vector already on the simplex is its own projection, and the
 * uniform case yields exactly {@code 1/n}. The last one carries the
 * backward-compatibility claim — see
 * {@link #uniformProjectionRescaledReproducesLegacyScore()}.</p>
 */
class SimplexProjectionTest {

    private static final double EPS = 1e-12;

    @Test
    void projectsArbitraryVectorOntoTheSimplex() {
        double[] projected = SimplexProjection.project(new double[]{2.0, -1.0, 0.5, 0.5});
        assertDistribution(projected, 4);
        assertEquals(0.0, projected[1], EPS, "a negative component must pin to zero");
    }

    @Test
    void uniformVectorProjectsToUniformSimplex() {
        double[] projected = SimplexProjection.project(new double[]{1, 1, 1, 1});
        assertDistribution(projected, 4);
        for (double value : projected) {
            assertEquals(0.25, value, EPS);
        }
    }

    @Test
    void scalarProjectsToOne() {
        assertEquals(1.0, SimplexProjection.project(new double[]{0.9})[0], EPS);
    }

    @Test
    void vectorAlreadyOnSimplexIsAFixedPoint() {
        assertArrayEquals(new double[]{0.4, 0.3, 0.2, 0.1},
                SimplexProjection.project(new double[]{0.4, 0.3, 0.2, 0.1}), EPS);
    }

    @Test
    void projectionIsIdempotent() {
        double[] once = SimplexProjection.project(new double[]{1.05, 0.98, 1.02, 0.95});
        assertArrayEquals(once, SimplexProjection.project(once), 1e-15);
    }

    @Test
    void allocatesAllMassToTheSingleNonZeroSignal() {
        // One signal dominates so far that the others must receive exactly zero: the
        // case clip-then-normalize cannot express without over-normalising the rest.
        double[] projected = SimplexProjection.project(new double[]{10.0, 0.0, 0.0, 0.0});
        assertDistribution(projected, 4);
        assertEquals(1.0, projected[0], EPS);
        assertEquals(0.0, projected[1], EPS);
    }

    @Test
    void nonFiniteInputIsSanitizedRatherThanPropagated() {
        assertDistribution(
                SimplexProjection.project(new double[]{1.0, Double.NaN, Double.POSITIVE_INFINITY}), 3);
        assertDistribution(
                SimplexProjection.project(new double[]{Double.NEGATIVE_INFINITY, Double.NaN, 2.0}), 3);
    }

    @Test
    void degenerateAllNonPositiveVectorFallsBackToUniform() {
        assertDistribution(SimplexProjection.project(new double[]{-1, -2, -3}), 3);
    }

    @Test
    void emptyVectorIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SimplexProjection.project(new double[0]));
        assertThrows(IllegalArgumentException.class, () -> SimplexProjection.project(null));
    }

    @Test
    void randomVectorsAlwaysProjectToAValidDistribution() {
        Random random = new Random(20241018L);
        for (int trial = 0; trial < 500; trial++) {
            int n = 1 + random.nextInt(6);
            double[] candidate = new double[n];
            for (int i = 0; i < n; i++) {
                candidate[i] = (random.nextDouble() - 0.5) * 20.0;
            }
            assertDistribution(SimplexProjection.project(candidate), n);
        }
    }

    /**
     * The backward-compatibility guarantee that makes the V20 migration unnecessary:
     * a uniform simplex scaled by {@code MAX_PRIORITY_SCORE} must reproduce the legacy
     * default weight of {@code 1.0} on an arbitrary feature vector, exactly.
     */
    @Test
    void uniformProjectionRescaledReproducesLegacyScore() {
        Random random = new Random(11L);
        double[] theta = SimplexProjection.project(new double[]{1, 1, 1, 1});
        for (int trial = 0; trial < 1_000; trial++) {
            double[] features = new double[4];
            double legacy = 0.0;
            double adapted = 0.0;
            for (int i = 0; i < 4; i++) {
                features[i] = random.nextDouble();
                legacy += 1.0 * features[i];
                adapted += theta[i] * features[i];
            }
            adapted *= PriorityCalibrationService.MAX_PRIORITY_SCORE;
            assertEquals(legacy, adapted, EPS,
                    "simplex-scored priority must match the legacy [0,4] value");
        }
    }

    private static void assertDistribution(double[] w, int expectedLength) {
        assertEquals(expectedLength, w.length);
        double sum = 0.0;
        for (double value : w) {
            assertTrue(value >= 0.0, "simplex component went negative: " + value);
            assertTrue(Double.isFinite(value), "simplex component is not finite");
            sum += value;
        }
        assertEquals(1.0, sum, EPS, "simplex components must sum to 1");
    }
}
