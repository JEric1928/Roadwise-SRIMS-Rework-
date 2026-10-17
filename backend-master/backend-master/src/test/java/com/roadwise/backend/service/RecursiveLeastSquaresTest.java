package com.roadwise.backend.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Discrete RLS: convergence, persistence round-trip, and fail-closed input handling.
 *
 * <p>The convergence case is a noiseless linear target, so the estimate has to reach
 * the true coefficients — a bug in the gain or covariance update would show up as
 * either divergence or a stalled estimate rather than a subtle offset.</p>
 */
class RecursiveLeastSquaresTest {

    @Test
    void convergesToNoiselessLinearTarget() {
        double[] truth = {2.0, -1.5, 0.75, 3.0};
        RecursiveLeastSquares rls = new RecursiveLeastSquares(4, 1.0);

        double[][] basis = {
                {1, 0, 0, 0},
                {0, 1, 0, 0},
                {0, 0, 1, 0},
                {0, 0, 0, 1},
                {1, 1, 0, 0},
                {0, 1, 1, 0}
        };

        for (int epoch = 0; epoch < 60; epoch++) {
            for (double[] x : basis) {
                double y = 0.0;
                for (int i = 0; i < truth.length; i++) {
                    y += truth[i] * x[i];
                }
                rls.update(x, y);
            }
        }

        double[] estimated = rls.weights();
        for (int i = 0; i < truth.length; i++) {
            assertEquals(truth[i], estimated[i], 0.05,
                    "coefficient " + i + " did not converge");
        }
    }

    @Test
    void covarianceStaysFiniteAndSymmetric() {
        RecursiveLeastSquares rls = new RecursiveLeastSquares(4);
        for (int i = 0; i < 25; i++) {
            rls.update(new double[]{1, 0.5, 0.25, 0.125}, 1.0 + (i % 3));
        }
        double[] p = rls.covariance();
        for (double value : p) {
            assertTrue(Double.isFinite(value), "covariance leaked a non-finite value");
        }
        // Symmetry is the invariant that keeps the estimate from drifting.
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                assertEquals(p[r * 4 + c], p[c * 4 + r], 1e-9);
            }
        }
    }

    @Test
    void baselineWeightsStartAtZeroWithLargeCovariance() {
        // lambda = 1.0 pins the closed form: denominator = 1 + 100, so the active
        // coefficient lands on y * 100/101 after exactly one observation.
        RecursiveLeastSquares rls = new RecursiveLeastSquares(4, 1.0);
        assertArrayEquals(new double[4], rls.weights());
        applyOneObservationExpectedFirstStep(rls);
    }

    /**
     * With P = 100·I and a basis regressor, the very first update must move the active
     * coefficient to (almost exactly) the target: k = 100x / (1 + 100) ≈ 0.99·x.
     */
    private static void applyOneObservationExpectedFirstStep(RecursiveLeastSquares rls) {
        double[] updated = rls.update(new double[]{1, 0, 0, 0}, 2.0);
        assertEquals(2.0 * (100.0 / 101.0), updated[0], 1e-9);
        assertEquals(0.0, updated[1], 1e-12);
    }

    @Test
    void invalidForglettingFactorIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RecursiveLeastSquares(4, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new RecursiveLeastSquares(4, 1.5));
        assertThrows(IllegalArgumentException.class, () -> new RecursiveLeastSquares(0));
    }

    @Test
    void nonFiniteInputsAreRejectedRatherThanAbsorbed() {
        RecursiveLeastSquares rls = new RecursiveLeastSquares(4);
        assertThrows(IllegalArgumentException.class,
                () -> rls.update(new double[]{1, 0, 0}, 1.0), "wrong arity must be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> rls.update(new double[]{1, 0, 0, 0}, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> rls.update(new double[]{1, Double.POSITIVE_INFINITY, 0, 0}, 1.0));
    }

    @Test
    void degenerateRegressorsLeaveTheEstimateUntouched() {
        RecursiveLeastSquares rls = new RecursiveLeastSquares(4);
        double[] before = rls.weights();
        rls.update(new double[]{0, 0, 0, 0}, 5.0);
        assertArrayEquals(before, rls.weights(),
                "a zero regressor carries no information and must not move the weights");
    }

    @Test
    void covarianceCsvRoundTrips() {
        RecursiveLeastSquares original = new RecursiveLeastSquares(4);
        original.update(new double[]{1, 0.5, 0, 0}, 3.0);
        original.update(new double[]{0, 1, 0.5, 0}, -1.0);

        RecursiveLeastSquares restored = RecursiveLeastSquares.restore(
                4, original.forgettingFactor(), original.weights(), original.covarianceCsv());

        assertArrayEquals(original.weights(), restored.weights(), 1e-12);
        assertArrayEquals(original.covariance(), restored.covariance(), 1e-12);
    }

    @Test
    void corruptCovarianceDegradesToWarmStartInsteadOfThrowing() {
        RecursiveLeastSquares restored = RecursiveLeastSquares.restore(
                4, 0.98, null, "not,a,matrix");
        assertNotNull(restored.weights());
        assertArrayEquals(new double[4], restored.weights(), 1e-12);
    }

    @Test
    void nonFiniteStoredWeightsAreIgnored() {
        RecursiveLeastSquares restored = RecursiveLeastSquares.restore(
                4, 0.98, new double[]{Double.NaN, 1, 1, 1}, null);
        assertArrayEquals(new double[4], restored.weights(), 1e-12,
                "a poisoned calibration row must not seed NaN weights");
    }
}
