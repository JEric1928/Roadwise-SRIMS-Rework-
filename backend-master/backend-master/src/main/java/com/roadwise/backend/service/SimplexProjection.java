package com.roadwise.backend.service;

import java.util.Arrays;

/**
 * Exact Euclidean projection onto the probability simplex.
 *
 * <p>Solves {@code argmin ||w - v||^2  s.t.  sum(w) = 1, w >= 0} — the projection
 * of an arbitrary weight vector onto {@code Δ^(n-1)}. Needed because calibrating
 * priority weights must keep them a convex combination: {@code sum(θ̂) = 1}
 * together with {@code θ̂ᵢ ∈ [0, 1]}.</p>
 *
 * <p>Why not clip-then-normalize: the two constraints are not independent. Clamping
 * a negative component to 0 and then dividing by the sum preserves the sum but
 * moves the vector off the nearest point, and once a component pins at 0 the
 * remaining components are over-normalized. Clipping first and normalizing second
 * also cannot represent "this signal gets zero weight and the rest keep their
 * relative ordering" at all. The algorithm below is O(n log n) and exact.</p>
 *
 * <p>Reference: Duchi, Shalev-Shwartz, Singer, Chandra, <i>Efficient Projections onto
 * the ℓ1-Ball for Learning in High Dimensions</i> (2008), §5.</p>
 */
public final class SimplexProjection {

    /** Relative slack allowed when testing the sort-derived threshold. */
    private static final double EPSILON = 1e-12;

    private SimplexProjection() {
        // static utility
    }

    /**
     * Project {@code v} onto the probability simplex.
     *
     * <p>Non-finite inputs are treated as {@code 0.0} rather than propagated: a
     * corrupt persisted weight must degrade to a usable distribution, not poison
     * every subsequent score. An empty or null input is a programming error and
     * throws.</p>
     *
     * @param v candidate weights
     * @return a new array {@code w} with {@code w[i] >= 0} and {@code sum(w) == 1}
     */
    public static double[] project(double[] v) {
        if (v == null || v.length == 0) {
            throw new IllegalArgumentException("cannot project an empty vector onto the simplex");
        }
        int n = v.length;
        double[] sanitized = new double[n];
        for (int i = 0; i < n; i++) {
            sanitized[i] = Double.isFinite(v[i]) ? v[i] : 0.0;
        }

        // 1. Sort descending.
        double[] sorted = sanitized.clone();
        Arrays.sort(sorted);
        for (int i = 0, j = n - 1; i < j; i++, j--) {
            double tmp = sorted[i];
            sorted[i] = sorted[j];
            sorted[j] = tmp;
        }

        // 2. Find the largest rho such that u(rho) - (1/rho) * (cumsum(rho) - 1) > 0.
        int rho = 0;
        double cumulative = 0.0;
        for (int j = 1; j <= n; j++) {
            cumulative += sorted[j - 1];
            double candidate = sorted[j - 1] - (cumulative - 1.0) / j;
            if (candidate > EPSILON) {
                rho = j;
            }
        }
        if (rho == 0) {
            // Degenerate: every component sits at or below the threshold. The
            // uniform distribution is the projection of a constant vector.
            double uniform = 1.0 / n;
            double[] result = new double[n];
            Arrays.fill(result, uniform);
            return result;
        }

        double threshold = 0.0;
        for (int j = 1; j <= rho; j++) {
            threshold += sorted[j - 1];
        }
        threshold = (threshold - 1.0) / rho;

        double[] projected = new double[n];
        double total = 0.0;
        for (int i = 0; i < n; i++) {
            double value = sanitized[i] - threshold;
            projected[i] = value > 0.0 ? value : 0.0;
            total += projected[i];
        }

        if (!(total > 0.0) || !Double.isFinite(total)) {
            double uniform = 1.0 / n;
            Arrays.fill(projected, uniform);
            return projected;
        }
        // Absorb floating-point drift so sum(w) is 1 to the last bit.
        for (int i = 0; i < n; i++) {
            projected[i] /= total;
        }
        return projected;
    }
}
