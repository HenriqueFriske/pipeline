package org.apache.commons.math3.linear;

import org.apache.commons.math3.exception.NumberIsTooLargeException;
import org.apache.commons.math3.exception.util.LocalizedFormats;
import org.apache.commons.math3.util.FastMath;
import org.apache.commons.math3.util.Precision;

/**
 * Calculates the compact Singular Value Decomposition of a matrix.
 * <p>
 * The Singular Value Decomposition of matrix A is a set of three matrices: U,
 * &Sigma; and V such that A = U &times; &Sigma; &times; V<sup>T</sup>. Let A be
 * a m &times; n matrix, then U is a m &times; p orthogonal matrix, &Sigma; is a
 * p &times; p diagonal matrix with positive or null elements, V is a p &times;
 * n orthogonal matrix (hence V<sup>T</sup> is also orthogonal) where
 * p=min(m,n).
 * </p>
 * <p>This class is similar to the class with similar name from the
 * <a href="http://math.nist.gov/javanumerics/jama/">JAMA</a> library, with the
 * following changes:</p>
 * <ul>
 *   <li>the {@code norm2} method which has been renamed as {@link #getNorm()
 *   getNorm},</li>
 *   <li>the {@code cond} method which has been renamed as {@link
 *   #getConditionNumber() getConditionNumber},</li>
 *   <li>the {@code rank} method which has been renamed as {@link #getRank()
 *   getRank},</li>
 *   <li>a {@link #getUT() getUT} method has been added,</li>
 *   <li>a {@link #getVT() getVT} method has been added,</li>
 *   <li>a {@link #getSolver() getSolver} method has been added,</li>
 *   <li>a {@link #getCovariance(double) getCovariance} method has been added.</li>
 * </ul>
 * @see <a href="http://mathworld.wolfram.com/SingularValueDecomposition.html">MathWorld</a>
 * @see <a href="http://en.wikipedia.org/wiki/Singular_value_decomposition">Wikipedia</a>
 * @version $Id$
 * @since 2.0 (changed to concrete class in 3.0)
 */
public class SingularValueDecomposition {
    /** Relative threshold for small singular values. */
    private static final double EPS = 0x1.0p-52;
    /** Absolute threshold for small singular values. */
    private static final double TINY = 0x1.0p-966;

    /** Computed singular values. */
    private final double[] singularValues;
    /** Number of rows in the internally used (potentially transposed) matrix. Always max(original rows, original columns). */
    private final int m;
    /** Number of columns in the internally used (potentially transposed) matrix. Always min(original rows, original columns). */
    private final int n;
    /** Indicator for whether the input matrix was transposed to satisfy m >= n. */
    private final boolean transposed;
    /** Cached value of U matrix. */
    private final RealMatrix cachedU;
    /** Cached value of transposed U matrix. */
    private RealMatrix cachedUt;
    /** Cached value of S (diagonal) matrix. */
    private RealMatrix cachedS;
    /** Cached value of V matrix. */
    private final RealMatrix cachedV;
    /** Cached value of transposed V matrix. */
    private RealMatrix cachedVt;
    /** Tolerance value for small singular values, calculated once singularValues are computed. */
    private final double toleranceForSingularValues;

    /**
     * Calculates the compact Singular Value Decomposition of the given matrix.
     * The algorithm ensures that the internal matrix 'A' has m >= n for computation,
     * transposing the input matrix if necessary and adjusting U and V at the end.
     *
     * @param matrix Matrix to decompose.
     */
    public SingularValueDecomposition(final RealMatrix matrix) {
        final double[][] A;

        // Phase 0: Initialize dimensions and determine if transposition is needed
        // to ensure m >= n for the internal algorithm.
        if (matrix.getRowDimension() < matrix.getColumnDimension()) {
            transposed = true;
            A = matrix.transpose().getData();
            m = matrix.getColumnDimension();
            n = matrix.getRowDimension();
        } else {
            transposed = false;
            A = matrix.getData();
            m = matrix.getRowDimension();
            n = matrix.getColumnDimension();
        }

        singularValues = new double[n];
        final double[][] U = new double[m][n];
        final double[][] V = new double[n][n];
        final double[] e = new double[n]; // Super-diagonal elements of the bidiagonal matrix
        final double[] work = new double[m]; // Temporary working array for Householder transformations

        // Phase 1: Reduce A to bidiagonal form
        // Stores diagonal elements in 'singularValues' and super-diagonal elements in 'e'.
        // U and V accumulate the Householder transformations.
        reduceToBidiagonalForm(A, singularValues, e, U, V, work);

        // Phase 2: Generate U from column transformations
        // This reconstructs the orthogonal matrix U from the accumulated Householder vectors.
        generateOrthogonalU(U, singularValues, m, n);

        // Phase 3: Generate V from row transformations
        // This reconstructs the orthogonal matrix V from the accumulated Householder vectors.
        generateOrthogonalV(V, e, n);

        // Phase 4: Main iteration loop for singular values (QR iteration)
        // This process converges the bidiagonal matrix to a diagonal one, yielding singular values
        // and updating U and V accordingly.
        computeSingularValuesAndVectors(singularValues, e, U, V, m, n);

        // Phase 5: Post-processing and caching
        // Calculate the tolerance for small singular values used in rank and pseudo-inverse calculations.
        this.toleranceForSingularValues = FastMath.max(m * singularValues[0] * EPS,
                                                       FastMath.sqrt(Precision.SAFE_MIN));

        // Cache U and V matrices. If original matrix was transposed, swap U and V.
        if (!transposed) {
            cachedU = MatrixUtils.createRealMatrix(U);
            cachedV = MatrixUtils.createRealMatrix(V);
        } else {
            cachedU = MatrixUtils.createRealMatrix(V);
            cachedV = MatrixUtils.createRealMatrix(U);
        }
    }

    /**
     * Reduces the input matrix A to bidiagonal form using Householder transformations.
     * Stores diagonal elements in 's' and super-diagonal elements in 'e'.
     * U and V accumulate the Householder transformations.
     *
     * @param A The matrix to be reduced, modified in place.
     * @param s Array to store diagonal elements (singular values).
     * @param e Array to store super-diagonal elements.
     * @param U Accumulator for column transformations (Householder vectors for U).
     * @param V Accumulator for row transformations (Householder vectors for V).
     * @param work Temporary working array.
     */
    private void reduceToBidiagonalForm(final double[][] A, final double[] s, final double[] e,
                                        final double[][] U, final double[][] V, final double[] work) {
        // 'numColTransforms' is the number of columns that will have column transformations.
        // It's effectively min(m-1, n).
        final int numColTransforms = FastMath.min(m - 1, n);
        // 'numRowTransforms' is the number of rows that will have row transformations.
        // It's effectively max(0, n-2).
        final int numRowTransforms = FastMath.max(0, n - 2);

        for (int k = 0; k < FastMath.max(numColTransforms, numRowTransforms); k++) {
            // Apply Householder transformation to the k-th column to zero out elements below the diagonal.
            if (k < numColTransforms) {
                applyHouseholderColumnTransformation(A, s, k);
            }

            // Apply the column transformation to subsequent columns of the matrix A
            // and store the k-th row of A into 'e' for the next row transformation.
            for (int j = k + 1; j < n; j++) {
                if (k < numColTransforms && s[k] != 0) {
                    applyTransformationToSubsequentColumn(A, k, j);
                }
                e[j] = A[k][j]; // Store k-th row of A into e for row transformation
            }

            // Store the Householder vector for the k-th column in U.
            if (k < numColTransforms) {
                for (int i = k; i < m; i++) {
                    U[i][k] = A[i][k];
                }
            }

            // Apply Householder transformation to the k-th row (from 'e') to zero out elements to the right of the super-diagonal.
            if (k < numRowTransforms) {
                applyHouseholderRowTransformation(A, e, V, work, k);
            }
        }

        // Final setup for the bidiagonal matrix elements if m or n dimensions were unusual.
        int p = n; // 'p' here is simply n, denoting the size of the bidiagonal matrix to work with.
        if (numColTransforms < n) {
            s[numColTransforms] = A[numColTransforms][numColTransforms];
        }
        if (m < p) {
            s[p - 1] = 0;
        }
        if (numRowTransforms + 1 < p) {
            e[numRowTransforms] = A[numRowTransforms][p - 1];
        }
        e[p - 1] = 0;
    }

    /**
     * Applies a Householder transformation to the k-th column of matrix A.
     * The k-th diagonal element is stored in s[k], and the Householder vector is stored in A[*,k].
     *
     * @param A The matrix, modified in place.
     * @param s Array for singular values (diagonal elements).
     * @param k Current column index.
     */
    private void applyHouseholderColumnTransformation(final double[][] A, final double[] s, final int k) {
        // Compute 2-norm of k-th column below row k without under/overflow.
        s[k] = 0;
        for (int i = k; i < m; i++) {
            s[k] = FastMath.hypot(s[k], A[i][k]);
        }
        if (s[k] != 0) {
            // Adjust sign to avoid cancellation and normalize the column.
            if (A[k][k] < 0) {
                s[k] = -s[k];
            }
            for (int i = k; i < m; i++) {
                A[i][k] /= s[k];
            }
            A[k][k] += 1; // Store the Householder reflector element.
        }
        s[k] = -s[k]; // The actual diagonal element for the bidiagonal matrix.
    }

    /**
     * Applies the computed column Householder transformation to subsequent columns of matrix A.
     *
     * @param A The matrix, modified in place.
     * @param k Current column index of the Householder vector.
     * @param j Column index to which the transformation is applied.
     */
    private void applyTransformationToSubsequentColumn(final double[][] A, final int k, final int j) {
        double dotProduct = 0;
        for (int i = k; i < m; i++) {
            dotProduct += A[i][k] * A[i][j];
        }
        final double factor = -dotProduct / A[k][k];
        for (int i = k; i < m; i++) {
            A[i][j] += factor * A[i][k];
        }
    }

    /**
     * Applies a Householder transformation to the k-th row (represented by 'e') of matrix A.
     * The k-th super-diagonal element is stored in e[k], and the Householder vector is stored in V[*,k].
     *
     * @param A The matrix, modified in place by applying the transformation.
     * @param e Array for super-diagonal elements.
     * @param V Accumulator for row transformations (Householder vectors for V).
     * @param work Temporary working array.
     * @param k Current row index.
     */
    private void applyHouseholderRowTransformation(final double[][] A, final double[] e,
                                                   final double[][] V, final double[] work, final int k) {
        // Compute 2-norm of elements in 'e' from k+1 to n-1 without under/overflow.
        e[k] = 0;
        for (int i = k + 1; i < n; i++) {
            e[k] = FastMath.hypot(e[k], e[i]);
        }
        if (e[k] != 0) {
            // Adjust sign and normalize.
            if (e[k + 1] < 0) {
                e[k] = -e[k];
            }
            for (int i = k + 1; i < n; i++) {
                e[i] /= e[k];
            }
            e[k + 1] += 1; // Store the Householder reflector element.
        }
        e[k] = -e[k]; // The actual super-diagonal element for the bidiagonal matrix.

        // Apply the row transformation to the submatrix A[k+1..m-1][k+1..n-1].
        if (k + 1 < m && e[k] != 0) {
            for (int i = k + 1; i < m; i++) {
                work[i] = 0;
            }
            for (int j = k + 1; j < n; j++) {
                for (int i = k + 1; i < m; i++) {
                    work[i] += e[j] * A[i][j];
                }
            }
            for (int j = k + 1; j < n; j++) {
                final double factor = -e[j] / e[k + 1];
                for (int i = k + 1; i < m; i++) {
                    A[i][j] += factor * work[i];
                }
            }
        }

        // Place the Householder vector for the k-th row in V.
        for (int i = k + 1; i < n; i++) {
            V[i][k] = e[i];
        }
    }

    /**
     * Generates the orthogonal matrix U from the accumulated Householder transformations.
     * The transformations are applied in reverse order to reconstruct U.
     *
     * @param U The matrix where Householder vectors are stored, modified to become the orthogonal U matrix.
     * @param s Array of singular values (diagonal elements).
     * @param m Number of rows in U.
     * @param n Number of columns in U.
     */
    private void generateOrthogonalU(final double[][] U, final double[] s, final int m, final int n) {
        final int numColTransforms = FastMath.min(m - 1, n);

        // Initialize the trailing columns of U as an identity matrix.
        for (int j = numColTransforms; j < n; j++) {
            for (int i = 0; i < m; i++) {
                U[i][j] = 0;
            }
            U[j][j] = 1; // Only applies if j < m, otherwise U[j][j] remains 0
        }

        // Apply Householder transformations in reverse order to build U.
        for (int k = numColTransforms - 1; k >= 0; k--) {
            if (s[k] != 0) {
                // Apply the transformation to subsequent columns U[*,j] for j > k.
                for (int j = k + 1; j < n; j++) {
                    double dotProduct = 0;
                    for (int i = k; i < m; i++) {
                        dotProduct += U[i][k] * U[i][j];
                    }
                    final double factor = -dotProduct / U[k][k];
                    for (int i = k; i < m; i++) {
                        U[i][j] += factor * U[i][k];
                    }
                }
                // Adjust the Householder vector itself.
                for (int i = k; i < m; i++) {
                    U[i][k] = -U[i][k];
                }
                U[k][k] = 1 + U[k][k]; // Set the diagonal element to 1 + original reflector value.
                // Zero out elements above the diagonal in the current column (U[i][k] for i < k-1).
                for (int i = 0; i < k - 1; i++) {
                    U[i][k] = 0;
                }
            } else {
                // If s[k] is zero, the transformation was trivial, set column to identity-like.
                for (int i = 0; i < m; i++) {
                    U[i][k] = 0;
                }
                U[k][k] = 1;
            }
        }
    }

    /**
     * Generates the orthogonal matrix V from the accumulated Householder transformations.
     * The transformations are applied in reverse order to reconstruct V.
     *
     * @param V The matrix where Householder vectors are stored, modified to become the orthogonal V matrix.
     * @param e Array of super-diagonal elements.
     * @param n Dimension of V (n x n).
     */
    private void generateOrthogonalV(final double[][] V, final double[] e, final int n) {
        final int numRowTransforms = FastMath.max(0, n - 2);

        // Apply Householder transformations in reverse order to build V.
        for (int k = n - 1; k >= 0; k--) {
            if (k < numRowTransforms && e[k] != 0) {
                // Apply transformation to subsequent columns V[*,j] for j > k.
                for (int j = k + 1; j < n; j++) {
                    double dotProduct = 0;
                    for (int i = k + 1; i < n; i++) {
                        dotProduct += V[i][k] * V[i][j];
                    }
                    final double factor = -dotProduct / V[k + 1][k];
                    for (int i = k + 1; i < n; i++) {
                        V[i][j] += factor * V[i][k];
                    }
                }
            }
            // Initialize current column and set diagonal to 1.
            for (int i = 0; i < n; i++) {
                V[i][k] = 0;
            }
            V[k][k] = 1;
        }
    }

    /**
     * Performs the QR iteration to compute singular values and their corresponding vectors.
     * This loop iteratively reduces the bidiagonal matrix to a diagonal form.
     *
     * @param s Array of diagonal elements (singular values), modified in place.
     * @param e Array of super-diagonal elements, modified in place.
     * @param U Orthogonal matrix U, updated in place.
     * @param V Orthogonal matrix V, updated in place.
     * @param m Number of rows of U.
     * @param n Number of columns of U (and rows/columns of V).
     */
    private void computeSingularValuesAndVectors(final double[] s, final double[] e,
                                                 final double[][] U, final double[][] V,
                                                 final int m, final int n) {
        int currentDimension = n; // 'p' in original code: current effective dimension of the subproblem.
        // int iterationCount = 0; // The original code increments iter but does not use it to break loop for too many iterations

        while (currentDimension > 0) {
            int k; // Index to identify a negligible element.
            int iterationCase; // 'kase' in original code, determines the type of QR step.

            // This section inspects for negligible elements in the 's' and 'e' arrays.
            // It sets 'iterationCase' and 'k' based on the structure of the bidiagonal matrix.
            for (k = currentDimension - 2; k >= 0; k--) {
                final double threshold
                    = TINY + EPS * (FastMath.abs(s[k]) +
                                    FastMath.abs(s[k + 1]));
                // The condition `!(FastMath.abs(e[k]) > threshold)` is used to correctly handle NaN values.
                if (!(FastMath.abs(e[k]) > threshold)) {
                    e[k] = 0;
                    break;
                }
            }

            if (k == currentDimension - 2) {
                iterationCase = 4; // Case 4: e[p-1] is negligible (convergence).
            } else {
                int ks; // Index for searching negligible s[ks].
                for (ks = currentDimension - 1; ks >= k; ks--) {
                    if (ks == k) {
                        break;
                    }
                    final double t = (ks != currentDimension ? FastMath.abs(e[ks]) : 0) +
                        (ks != k + 1 ? FastMath.abs(e[ks - 1]) : 0);
                    if (FastMath.abs(s[ks]) <= TINY + EPS * t) {
                        s[ks] = 0;
                        break;
                    }
                }
                if (ks == k) {
                    iterationCase = 3; // Case 3: e[k-1] is negligible, perform QR step.
                } else if (ks == currentDimension - 1) {
                    iterationCase = 1; // Case 1: s[p] and e[k-1] are negligible, deflate s[p].
                } else {
                    iterationCase = 2; // Case 2: s[k] is negligible, split at s[k].
                    k = ks;
                }
            }
            k++; // Adjust k for the start of the current subproblem.

            // Perform the task indicated by iterationCase.
            switch (iterationCase) {
                case 1: // Deflate negligible s(currentDimension).
                    deflateSmallestSingularValue(k, currentDimension, s, e, V, n);
                    // iterationCount is incremented in original code, but not used. Removing for clarity.
                    break;
                case 2: // Split at negligible s(k).
                    splitAtSmallSingularValue(k, currentDimension, s, e, U, m);
                    // iterationCount is incremented in original code, but not used. Removing for clarity.
                    break;
                case 3: // Perform one QR step (implicit shift).
                    performImplicitShiftQRStep(k, currentDimension, s, e, U, V, m, n);
                    // iterationCount is incremented, but not used to break loop.
                    break;
                case 4: // Convergence.
                    handleConvergenceAndReorder(k, currentDimension, s, U, V, m, n);
                    // iterationCount = 0 in original code.
                    currentDimension--; // Reduce the effective dimension of the problem.
                    break;
                default:
                    // This case should theoretically not be reached.
                    break;
            }
        }
    }

    /**
     * Handles Case 1: Deflate the smallest singular value s(p).
     * This involves applying a rotation to columns of V to move a small super-diagonal element.
     *
     * @param k Starting index of the current subproblem.
     * @param p Current dimension of the subproblem.
     * @param s Array of singular values.
     * @param e Array of super-diagonal elements.
     * @param V Orthogonal matrix V, updated in place.
     * @param n Overall dimension for V.
     */
    private void deflateSmallestSingularValue(final int k, final int p, final double[] s, final double[] e,
                                              final double[][] V, final int n) {
        double f = e[p - 2];
        e[p - 2] = 0;
        for (int j = p - 2; j >= k; j--) {
            double t = FastMath.hypot(s[j], f);
            final double cs = s[j] / t; // Cosine of the rotation angle.
            final double sn = f / t;    // Sine of the rotation angle.
            s[j] = t; // Update singular value.
            if (j != k) {
                f = -sn * e[j - 1];
                e[j - 1] = cs * e[j - 1];
            }

            // Apply rotation to V columns V[*,j] and V[*,p-1].
            for (int i = 0; i < n; i++) {
                t = cs * V[i][j] + sn * V[i][p - 1];
                V[i][p - 1] = -sn * V[i][j] + cs * V[i][p - 1];
                V[i][j] = t;
            }
        }
    }

    /**
     * Handles Case 2: Split the problem at a negligible singular value s(k).
     * This involves applying a rotation to columns of U to move a small diagonal element.
     *
     * @param k Starting index of the current subproblem.
     * @param p Current dimension of the subproblem.
     * @param s Array of singular values.
     * @param e Array of super-diagonal elements.
     * @param U Orthogonal matrix U, updated in place.
     * @param m Overall dimension for U.
     */
    private void splitAtSmallSingularValue(final int k, final int p, final double[] s, final double[] e,
                                            final double[][] U, final int m) {
        double f = e[k - 1];
        e[k - 1] = 0;
        for (int j = k; j < p; j++) {
            double t = FastMath.hypot(s[j], f);
            final double cs = s[j] / t;
            final double sn = f / t;
            s[j] = t;
            f = -sn * e[j];
            e[j] = cs * e[j];

            // Apply rotation to U columns U[*,j] and U[*,k-1].
            for (int i = 0; i < m; i++) {
                t = cs * U[i][j] + sn * U[i][k - 1];
                U[i][k - 1] = -sn * U[i][j] + cs * U[i][k - 1];
                U[i][j] = t;
            }
        }
    }

    /**
     * Handles Case 3: Perform one implicit-shift QR step.
     * This is the core iterative step to reduce the bidiagonal matrix to diagonal form.
     *
     * @param k Starting index of the current subproblem.
     * @param p Current dimension of the subproblem.
     * @param s Array of singular values.
     * @param e Array of super-diagonal elements.
     * @param U Orthogonal matrix U, updated in place.
     * @param V Orthogonal matrix V, updated in place.
     * @param m Number of rows of U.
     * @param n Number of columns of U (and rows/columns of V).
     */
    private void performImplicitShiftQRStep(final int k, final int p, final double[] s, final double[] e,
                                            final double[][] U, final double[][] V, final int m, final int n) {
        // Calculate the shift using Wilkinson's method for improved convergence.
        final double s_p_minus_1 = s[p - 1];
        final double s_p_minus_2 = s[p - 2];
        final double e_p_minus_2 = e[p - 2];
        final double s_k = s[k];
        final double e_k = e[k];

        final double maxPm1Pm2 = FastMath.max(FastMath.abs(s_p_minus_1), FastMath.abs(s_p_minus_2));
        final double scale = FastMath.max(FastMath.max(maxPm1Pm2, FastMath.abs(e_p_minus_2)),
                                          FastMath.max(FastMath.abs(s_k), FastMath.abs(e_k)));

        // Scale elements to avoid under/overflow during shift calculation.
        final double scaled_s_p = s_p_minus_1 / scale;
        final double scaled_s_p_minus_1 = s_p_minus_2 / scale;
        final double scaled_e_p_minus_1 = e_p_minus_2 / scale;
        final double scaled_s_k = s_k / scale;
        final double scaled_e_k = e_k / scale;

        final double b = ((scaled_s_p_minus_1 + scaled_s_p) * (scaled_s_p_minus_1 - scaled_s_p) +
                          scaled_e_p_minus_1 * scaled_e_p_minus_1) / 2.0;
        final double c = (scaled_s_p * scaled_e_p_minus_1) * (scaled_s_p * scaled_e_p_minus_1);
        
        double shift = 0;
        if (b != 0 || c != 0) {
            shift = FastMath.sqrt(b * b + c);
            if (b < 0) {
                shift = -shift;
            }
            shift = c / (b + shift); // More stable computation of shift.
        }

        double f = (scaled_s_k + scaled_s_p) * (scaled_s_k - scaled_s_p) + shift; // Initial value for chase process.
        double g = scaled_s_k * scaled_e_k;

        // Chase zeros down the diagonal, applying rotations.
        for (int j = k; j < p - 1; j++) {
            double t = FastMath.hypot(f, g);
            double cs = f / t;
            double sn = g / t;
            if (j != k) {
                e[j - 1] = t; // Update super-diagonal element.
            }
            f = cs * s[j] + sn * e[j];
            e[j] = cs * e[j] - sn * s[j];
            g = sn * s[j + 1];
            s[j + 1] = cs * s[j + 1];

            // Apply rotation to V columns.
            for (int i = 0; i < n; i++) {
                t = cs * V[i][j] + sn * V[i][j + 1];
                V[i][j + 1] = -sn * V[i][j] + cs * V[i][j + 1];
                V[i][j] = t;
            }
            t = FastMath.hypot(f, g);
            cs = f / t;
            sn = g / t;
            s[j] = t;
            f = cs * e[j] + sn * s[j + 1];
            s[j + 1] = -sn * e[j] + cs * s[j + 1];
            g = sn * e[j + 1];
            e[j + 1] = cs * e[j + 1];
            // Apply rotation to U columns if within bounds.
            if (j < m - 1) {
                for (int i = 0; i < m; i++) {
                    t = cs * U[i][j] + sn * U[i][j + 1];
                    U[i][j + 1] = -sn * U[i][j] + cs * U[i][j + 1];
                    U[i][j] = t;
                }
            }
        }
        e[p - 2] = f; // Final super-diagonal element.
    }

    /**
     * Handles Case 4: Convergence of a singular value. Makes it positive and reorders if necessary.
     *
     * @param k Starting index of the current subproblem.
     * @param p Current dimension of the subproblem.
     * @param s Array of singular values, modified in place.
     * @param U Orthogonal matrix U, updated in place.
     * @param V Orthogonal matrix V, updated in place.
     * @param m Number of rows of U.
     * @param n Number of columns of U (and rows/columns of V).
     */
    private void handleConvergenceAndReorder(final int k, final int p, final double[] s,
                                             final double[][] U, final double[][] V, final int m, final int n) {
        // Ensure the converged singular value is positive.
        if (s[k] <= 0) {
            s[k] = (s[k] < 0) ? -s[k] : 0;
            // Negate corresponding V column to maintain consistency.
            for (int i = 0; i < n; i++) {
                V[i][k] = -V[i][k];
            }
        }
        // Order the singular values in non-increasing order (and swap corresponding U and V columns).
        while (k < p - 1) {
            if (s[k] >= s[k + 1]) {
                break; // Already in order or reached end of subproblem.
            }
            // Swap singular values.
            double tempS = s[k];
            s[k] = s[k + 1];
            s[k + 1] = tempS;

            // Swap corresponding columns in V.
            if (k < n - 1) {
                for (int i = 0; i < n; i++) {
                    double tempV = V[i][k + 1];
                    V[i][k + 1] = V[i][k];
                    V[i][k] = tempV;
                }
            }
            // Swap corresponding columns in U.
            if (k < m - 1) {
                for (int i = 0; i < m; i++) {
                    double tempU = U[i][k + 1];
                    U[i][k + 1] = U[i][k];
                    U[i][k] = tempU;
                }
            }
            k++;
        }
    }

    /**
     * Returns the matrix U of the decomposition.
     * <p>U is an orthogonal matrix, i.e. its transpose is also its inverse.</p>
     * @return the U matrix
     * @see #getUT()
     */
    public RealMatrix getU() {
        return cachedU;
    }

    /**
     * Returns the transpose of the matrix U of the decomposition.
     * <p>U is an orthogonal matrix, i.e. its transpose is also its inverse.</p>
     * @return the U matrix
     * @see #getU()
     */
    public RealMatrix getUT() {
        if (cachedUt == null) {
            cachedUt = getU().transpose();
        }
        return cachedUt;
    }

    /**
     * Returns the diagonal matrix &Sigma; of the decomposition.
     * <p>&Sigma; is a diagonal matrix. The singular values are provided in
     * non-increasing order, for compatibility with Jama.</p>
     * @return the &Sigma; matrix
     */
    public RealMatrix getS() {
        if (cachedS == null) {
            cachedS = MatrixUtils.createRealDiagonalMatrix(singularValues);
        }
        return cachedS;
    }

    /**
     * Returns the diagonal elements of the matrix &Sigma; of the decomposition.
     * <p>The singular values are provided in non-increasing order, for
     * compatibility with Jama.</p>
     * @return the diagonal elements of the &Sigma; matrix
     */
    public double[] getSingularValues() {
        return singularValues.clone();
    }

    /**
     * Returns the matrix V of the decomposition.
     * <p>V is an orthogonal matrix, i.e. its transpose is also its inverse.</p>
     * @return the V matrix
     * @see #getVT()
     */
    public RealMatrix getV() {
        return cachedV;
    }

    /**
     * Returns the transpose of the matrix V of the decomposition.
     * <p>V is an orthogonal matrix, i.e. its transpose is also its inverse.</p>
     * @return the V matrix
     * @see #getV()
     */
    public RealMatrix getVT() {
        if (cachedVt == null) {
            cachedVt = getV().transpose();
        }
        return cachedVt;
    }

    /**
     * Returns the n &times; n covariance matrix.
     * <p>The covariance matrix is V &times; J &times; V<sup>T</sup>
     * where J is the diagonal matrix of the inverse of the squares of
     * the singular values.</p>
     * @param minSingularValue value below which singular values are ignored
     * (a 0 or negative value implies all singular value will be used)
     * @return covariance matrix
     * @exception IllegalArgumentException if minSingularValue is larger than
     * the largest singular value, meaning all singular values are ignored
     */
    public RealMatrix getCovariance(final double minSingularValue) {
        // Determine the number of singular values to consider based on minSingularValue.
        int dimensionOfConsideredSingularValues = 0;
        while (dimensionOfConsideredSingularValues < singularValues.length &&
               singularValues[dimensionOfConsideredSingularValues] >= minSingularValue) {
            ++dimensionOfConsideredSingularValues;
        }

        if (dimensionOfConsideredSingularValues == 0) {
            throw new NumberIsTooLargeException(LocalizedFormats.TOO_LARGE_CUTOFF_SINGULAR_VALUE,
                                                minSingularValue, singularValues[0], true);
        }

        // Create a temporary matrix to store V^T scaled by inverse singular values.
        final double[][] scaledVTData = new double[dimensionOfConsideredSingularValues][n];
        getVT().walkInOptimizedOrder(new DefaultRealMatrixPreservingVisitor() {
            /** {@inheritDoc} */
            @Override
            public void visit(final int row, final int column,
                    final double value) {
                // Only scale and store if the row corresponds to a considered singular value.
                if (row < dimensionOfConsideredSingularValues) {
                    scaledVTData[row][column] = value / singularValues[row];
                }
            }
        }, 0, dimensionOfConsideredSingularValues - 1, 0, n - 1); // Walk only the relevant part of VT.

        RealMatrix jv = new Array2DRowRealMatrix(scaledVTData, false);
        return jv.transpose().multiply(jv);
    }

    /**
     * Returns the L<sub>2</sub> norm of the matrix.
     * <p>The L<sub>2</sub> norm is max(|A &times; u|<sub>2</sub> /
     * |u|<sub>2</sub>), where |.|<sub>2</sub> denotes the vectorial 2-norm
     * (i.e. the traditional euclidian norm).</p>
     * @return norm
     */
    public double getNorm() {
        // The largest singular value is the L2 norm.
        return singularValues[0];
    }

    /**
     * Return the condition number of the matrix.
     * @return condition number of the matrix
     */
    public double getConditionNumber() {
        // Condition number is the ratio of the largest to the smallest non-zero singular value.
        return singularValues[0] / singularValues[n - 1];
    }

    /**
     * Computes the inverse of the condition number.
     * In cases of rank deficiency, the {@link #getConditionNumber() condition
     * number} will become undefined.
     *
     * @return the inverse of the condition number.
     */
    public double getInverseConditionNumber() {
        return singularValues[n - 1] / singularValues[0];
    }

    /**
     * Return the effective numerical matrix rank.
     * <p>The effective numerical rank is the number of non-negligible
     * singular values. The threshold used to identify non-negligible
     * terms is max(m,n) &times; ulp(s<sub>1</sub>) where ulp(s<sub>1</sub>)
     * is the least significant bit of the largest singular value.</p>
     * @return effective numerical matrix rank
     */
    public int getRank() {
        int rank = 0;
        for (int i = 0; i < singularValues.length; i++) {
            if (singularValues[i] > toleranceForSingularValues) {
                rank++;
            }
        }
        return rank;
    }

    /**
     * Get a solver for finding the A &times; X = B solution in least square sense.
     * @return a solver
     */
    public DecompositionSolver getSolver() {
        return new Solver(singularValues, getUT(), getV(), getRank() == m, toleranceForSingularValues);
    }

    /** Specialized solver for least squares problems using SVD. */
    private static class Solver implements DecompositionSolver {
        /** Pseudo-inverse of the initial matrix, (V * inv(S) * U^T). */
        private final RealMatrix pseudoInverse;
        /** Indicates if the original matrix was non-singular based on its rank relative to `m`. */
        private final boolean nonSingular;

        /**
         * Constructs a solver from the SVD decomposition results.
         *
         * @param singularValues Singular values of the original matrix.
         * @param uT U<sup>T</sup> matrix of the decomposition.
         * @param v V matrix of the decomposition.
         * @param isOriginalMatrixNonSingular True if the rank of the decomposed matrix equals its row dimension.
         * @param tolerance Tolerance for considering singular values as non-zero.
         */
        private Solver(final double[] singularValues, final RealMatrix uT,
                       final RealMatrix v, final boolean isOriginalMatrixNonSingular, final double tolerance) {
            // Create a scaled U^T matrix: U^T where each row is scaled by 1/s_i if s_i > tolerance, else 0.
            final double[][] scaledUTData = uT.getData();
            for (int i = 0; i < singularValues.length; ++i) {
                final double scalingFactor;
                if (singularValues[i] > tolerance) {
                    scalingFactor = 1 / singularValues[i];
                } else {
                    scalingFactor = 0; // Treat small singular values as zero for pseudo-inverse.
                }
                final double[] currentUTRow = scaledUTData[i];
                for (int j = 0; j < currentUTRow.length; ++j) {
                    currentUTRow[j] *= scalingFactor;
                }
            }
            // The pseudo-inverse is V * (scaled U^T).
            pseudoInverse = v.multiply(new Array2DRowRealMatrix(scaledUTData, false));
            this.nonSingular = isOriginalMatrixNonSingular;
        }

        /**
         * Solve the linear equation A &times; X = B in least square sense.
         * <p>
         * The m&times;n matrix A may not be square, the solution X is such that
         * ||A &times; X - B|| is minimal.
         * </p>
         * @param b Right-hand side of the equation A &times; X = B (as a vector).
         * @return a vector X that minimizes the two norm of A &times; X - B
         * @throws org.apache.commons.math3.exception.DimensionMismatchException
         * if the matrices dimensions do not match.
         */
        @Override
        public RealVector solve(final RealVector b) {
            return pseudoInverse.operate(b);
        }

        /**
         * Solve the linear equation A &times; X = B in least square sense.
         * <p>
         * The m&times;n matrix A may not be square, the solution X is such that
         * ||A &times; X - B|| is minimal. Therefore, X = A<sup>+</sup>B where A<sup>+</sup> is the pseudo-inverse.
         * </p>
         *
         * @param b Right-hand side of the equation A &times; X = B (as a matrix).
         * @return a matrix X that minimizes the two norm of A &times; X - B
         * @throws org.apache.commons.math3.exception.DimensionMismatchException
         * if the matrices dimensions do not match.
         */
        @Override
        public RealMatrix solve(final RealMatrix b) {
            return pseudoInverse.multiply(b);
        }

        /**
         * Check if the decomposed matrix is non-singular.
         *
         * @return {@code true} if the decomposed matrix is non-singular (i.e., full rank).
         */
        @Override
        public boolean isNonSingular() {
            return nonSingular;
        }

        /**
         * Get the pseudo-inverse of the decomposed matrix.
         *
         * @return the pseudo-inverse matrix.
         */
        @Override
        public RealMatrix getInverse() {
            return pseudoInverse;
        }
    }
}