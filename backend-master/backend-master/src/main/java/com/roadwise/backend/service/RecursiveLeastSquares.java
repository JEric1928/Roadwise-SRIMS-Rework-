package com.roadwise.backend.service;

/**
 * Discrete recursive least squares for a small linear model (4 parameters).
 *
 * <p>Standard exponentially-weighted RLS with forgetting factor {@code lambda}:</p>
 * <pre>
 *   k = P·x / (lambda + xᵀ·P·x)
 *   w = w + k·(y - wᵀ·x)
 *   P = (P - k·xᵀ·P) / lambda
 * </pre>
 *
 * <p>Deliberately not a matrix library: the whole point of the design is that
 * recalibrating priority weights costs a 4x4 update — microseconds — instead of
 * re-running a perception backbone over the historical corpus. At this size the
 * explicit loops are faster than any BLAS call and have no dependency.</p>
 *
 * <p>Invariant that matters: {@code P} stays symmetric positive-definite as long as
 * {@code lambda ∈ (0, 1]} and the initial {@code P} is {@code δ·I} with
 * {@code δ > 0}. A mutating API makes that easy to break, so the constructor owns
 * initialization and nothing else can reset the matrix.</p>
 */
public final class RecursiveLeastSquares {

    /** Forgetting factor: 1.0 keeps all history, lower values favour recent audits. */
    public static final double DEFAULT_FORGETTING_FACTOR = 0.98;

    /**
     * Initial covariance scale. Large relative to the data means "no prior belief, so
     * the first observations move the weights a lot" — the standard RLS warm start.
     */
    private static final double INITIAL_COVARIANCE = 100.0;

    private final int dimension;
    private final double lambda;

    private final double[] weights;
    /** Row-major d x d covariance. */
    private final double[] covariance;

    public RecursiveLeastSquares(int dimension) {
        this(dimension, DEFAULT_FORGETTING_FACTOR);
    }

    public RecursiveLeastSquares(int dimension, double lambda) {
        if (dimension < 1) {
            throw new IllegalArgumentException("dimension must be >= 1");
        }
        if (!(lambda > 0.0 && lambda <= 1.0)) {
            throw new IllegalArgumentException("forgetting factor must be in (0, 1]");
        }
        this.dimension = dimension;
        this.lambda = lambda;
        this.weights = new double[dimension];
        this.covariance = new double[dimension * dimension];
        for (int i = 0; i < dimension; i++) {
            covariance[i * dimension + i] = INITIAL_COVARIANCE;
        }
    }

    /**
     * Fold one observation into the estimate.
     *
     * @param x regressors, length {@code dimension}
     * @param y observed target value
     * @return the updated weights (the internal array — copy if you intend to keep it)
     */
    public double[] update(double[] x, double y) {
        if (x == null || x.length != dimension) {
            throw new IllegalArgumentException("expected " + dimension + " regressors");
        }
        if (!Double.isFinite(y)) {
            throw new IllegalArgumentException("target must be finite");
        }
        for (double value : x) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("regressors must be finite");
            }
        }
        int d = dimension;

        // Px = P · x
        double[] px = new double[d];
        for (int i = 0; i < d; i++) {
            double sum = 0.0;
            for (int j = 0; j < d; j++) {
                sum += covariance[i * d + j] * x[j];
            }
            px[i] = sum;
        }

        // denominator = lambda + xᵀ·P·x
        double denominator = lambda;
        for (int i = 0; i < d; i++) {
            denominator += x[i] * px[i];
        }
        if (!Double.isFinite(denominator) || Math.abs(denominator) < 1e-12) {
            // Degenerate regressors carry no information; skipping keeps P intact
            // rather than dividing by ~zero and destroying the estimate.
            return weights.clone();
        }

        // k = Px / denominator
        double[] gain = new double[d];
        for (int i = 0; i < d; i++) {
            gain[i] = px[i] / denominator;
        }

        // a priori error, using the pre-update weights
        double predicted = 0.0;
        for (int i = 0; i < d; i++) {
            predicted += weights[i] * x[i];
        }
        double error = y - predicted;

        for (int i = 0; i < d; i++) {
            weights[i] += gain[i] * error;
        }

        // P = (P - k·xᵀ·P) / lambda, with xᵀ·P read out of px
        for (int i = 0; i < d; i++) {
            for (int j = 0; j < d; j++) {
                covariance[i * d + j] = (covariance[i * d + j] - gain[i] * px[j]) / lambda;
            }
        }
        return weights.clone();
    }

    public double[] weights() {
        return weights.clone();
    }

    public double[] covariance() {
        return covariance.clone();
    }

    public int dimension() {
        return dimension;
    }

    public double forgettingFactor() {
        return lambda;
    }

    // ---- persistence of the covariance matrix --------------------------------

    /**
     * Restore a previous estimate. Falls back to a fresh warm start when the payload
     * is missing, the wrong length, or non-numeric — a corrupt calibration row must
     * degrade to the static ranking, never to an exception on the request path.
     */
    public static RecursiveLeastSquares restore(int dimension, double lambda,
                                                double[] previousWeights,
                                                String covarianceCsv) {
        RecursiveLeastSquares rls = new RecursiveLeastSquares(dimension, lambda);
        double[] parsed = parseCsv(covarianceCsv);
        if (parsed != null && parsed.length == dimension * dimension) {
            System.arraycopy(parsed, 0, rls.covariance, 0, parsed.length);
        }
        if (previousWeights != null && previousWeights.length == dimension) {
            for (double value : previousWeights) {
                if (!Double.isFinite(value)) {
                    // Leave the warm start untouched rather than seeding NaN.
                    return rls;
                }
            }
            System.arraycopy(previousWeights, 0, rls.weights, 0, dimension);
        }
        return rls;
    }

    /** Row-major CSV; commas only, so it stays readable in a psql session. */
    public String covarianceCsv() {
        StringBuilder sb = new StringBuilder(covariance.length * 12);
        for (int i = 0; i < covariance.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(covariance[i]);
        }
        return sb.toString();
    }

    private static double[] parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return null;
        }
        String[] parts = csv.split(",");
        double[] values = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                double value = Double.parseDouble(parts[i].trim());
                if (!Double.isFinite(value)) {
                    return null;
                }
                values[i] = value;
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return values;
    }
}
