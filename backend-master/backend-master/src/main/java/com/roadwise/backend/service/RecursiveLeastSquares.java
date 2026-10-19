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

    /**
     * Bounds on {@code tr(P)}. Below the floor the gain has collapsed and the
     * estimate has stopped learning; above the ceiling an exponentially-weighted
     * forgetting factor has inflated the covariance until a single observation
     * can swing the weights arbitrarily. Either way the deterministic remedy is
     * the warm start, which is exactly {@code INITIAL_COVARIANCE · I}.
     */
    private static final double MIN_TRACE = 1e-4;

    private static final double MAX_TRACE = 1e4;

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
        enforceTraceBound();
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
        if (parsed != null && parsed.length == dimension * dimension
                && isRestorableCovariance(parsed, dimension)) {
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

    // ---- covariance sanity ---------------------------------------------------

    /**
     * A persisted covariance is only adopted when it is symmetric, inside the trace
     * bounds, and positive-definite.
     *
     * <p>{@link #restore} previously accepted any payload of the right <em>length</em>.
     * A corrupt or hand-edited {@code covariance_json} could therefore install a
     * matrix with a zero or negative eigenvalue, after which every subsequent update
     * interpolates through a non-invertible prior and the estimate is meaningless —
     * silently, on the request path. Rejecting it here downgrades to the warm start,
     * which this class already documents as the safe failure mode.</p>
     */
    private static boolean isRestorableCovariance(double[] matrix, int dimension) {
        return isSymmetric(matrix, dimension)
                && isInsideTraceBounds(matrix, dimension)
                && isPositiveDefinite(matrix, dimension);
    }

    private static boolean isSymmetric(double[] matrix, int dimension) {
        for (int i = 0; i < dimension; i++) {
            for (int j = i + 1; j < dimension; j++) {
                double upper = matrix[i * dimension + j];
                double lower = matrix[j * dimension + i];
                double scale = Math.max(1.0, Math.max(Math.abs(upper), Math.abs(lower)));
                if (!(Math.abs(upper - lower) <= 1e-9 * scale)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Cholesky feasibility test. Cheaper than an eigenvalue decomposition, and the
     * factorisation succeeding is itself the positive-definiteness certificate.
     */
    private static boolean isPositiveDefinite(double[] matrix, int dimension) {
        double[] lower = new double[dimension * dimension];
        for (int i = 0; i < dimension; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = matrix[i * dimension + j];
                for (int k = 0; k < j; k++) {
                    sum -= lower[i * dimension + k] * lower[j * dimension + k];
                }
                if (i == j) {
                    if (!(sum > 0.0) || !Double.isFinite(sum)) {
                        return false;
                    }
                    lower[i * dimension + j] = Math.sqrt(sum);
                } else {
                    double pivot = lower[j * dimension + j];
                    if (!(pivot > 0.0)) {
                        return false;
                    }
                    lower[i * dimension + j] = sum / pivot;
                }
            }
        }
        return true;
    }

    private static boolean isInsideTraceBounds(double[] matrix, int dimension) {
        double trace = 0.0;
        for (int i = 0; i < dimension; i++) {
            trace += matrix[i * dimension + i];
        }
        return Double.isFinite(trace) && trace >= MIN_TRACE && trace <= MAX_TRACE;
    }

    /**
     * Reset to the warm start when {@code tr(P)} leaves its bounds. Applied after
     * every update as well as on restore, so long runs cannot accumulate drift into
     * a singular estimate.
     */
    private void enforceTraceBound() {
        if (isInsideTraceBounds(covariance, dimension)) {
            return;
        }
        for (int i = 0; i < dimension * dimension; i++) {
            covariance[i] = 0.0;
        }
        for (int i = 0; i < dimension; i++) {
            covariance[i * dimension + i] = INITIAL_COVARIANCE;
        }
    }
}
