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
    // ---- persisted-covariance guard ------------------------------------------
    //
    // restore() used to accept any payload of the right *length*. A corrupt or
    // hand-edited covariance_json could therefore install a matrix with a zero or
    // negative eigenvalue, after which every later update interpolates through a
    // non-invertible prior — silently, on the request path. Each case below must
    // degrade to the warm start (100·I) rather than throw or absorb the payload.

    @Test
    void asymmetricPersistedCovarianceDegradesToWarmStart() {
        double[] asymmetric = diagonal(new double[]{4, 4, 4, 4});
        asymmetric[1] = 98.0; // breaks symmetry only
        assertDegradesToWarmStart("asymmetric", csv(asymmetric));
    }

    @Test
    void nonPositiveDefinitePersistedCovarianceDegradesToWarmStart() {
        assertDegradesToWarmStart("negative diagonal", csv(diagonal(new double[]{-4, 4, 4, 4})));

        // Symmetric and trace-legal, but with a zero eigenvalue: (1,0,0,0) and
        // (0,1,0,0) rows are dependent. Only the Cholesky step catches this.
        double[] singular = new double[16];
        singular[0] = 1.0;
        singular[1] = 1.0;
        singular[4] = 1.0;
        singular[5] = 1.0;
        singular[10] = 1.0;
        singular[15] = 1.0;
        assertDegradesToWarmStart("singular block", csv(singular));
    }

    @Test
    void outOfBoundsTraceDegradesToWarmStart() {
        assertDegradesToWarmStart("trace above ceiling", csv(diagonal(new double[]{1e6, 1e6, 1e6, 1e6})));
        assertDegradesToWarmStart("trace below floor", csv(diagonal(new double[]{1e-9, 1e-9, 1e-9, 1e-9})));
    }

    @Test
    void legalPersistedCovarianceIsStillAdopted() {
        // Guards against an over-eager validator: 4·I is symmetric, positive-definite
        // and well inside the trace bounds, so it must survive the round trip.
        RecursiveLeastSquares restored = RecursiveLeastSquares.restore(
                4, 1.0, null, csv(diagonal(new double[]{4, 4, 4, 4})));
        assertArrayEquals(diagonal(new double[]{4, 4, 4, 4}), restored.covariance(), 1e-12);
    }

    @Test
    void traceStaysBoundedAcrossALongRun() {
        RecursiveLeastSquares rls = new RecursiveLeastSquares(4, 1.0);
        for (int i = 0; i < 400; i++) {
            rls.update(new double[]{1, 0.5, 0.25, 0.125}, 1.0 + (i % 3));
        }
        double[] p = rls.covariance();
        double trace = p[0] + p[5] + p[10] + p[15];
        assertTrue(trace >= 1e-4, "trace collapsed below the floor: " + trace);
        assertTrue(trace <= 1e4, "trace escaped the ceiling: " + trace);
        for (double value : p) {
            assertTrue(Double.isFinite(value), "covariance leaked a non-finite value");
        }
    }

    private static void assertDegradesToWarmStart(String label, String payload) {
        RecursiveLeastSquares restored = RecursiveLeastSquares.restore(4, 0.98, null, payload);
        assertArrayEquals(diagonal(new double[]{100, 100, 100, 100}), restored.covariance(), 1e-12,
                label + " covariance must degrade to the 100·I warm start");
    }

    private static double[] diagonal(double[] values) {
        int d = values.length;
        double[] matrix = new double[d * d];
        for (int i = 0; i < d; i++) {
            matrix[i * d + i] = values[i];
        }
        return matrix;
    }

    private static String csv(double[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(values[i]);
        }
        return sb.toString();
    }

}
