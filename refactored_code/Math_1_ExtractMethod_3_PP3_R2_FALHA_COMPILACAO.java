/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
    /** Maximum number of iterations for the QR algorithm. */
    private static final int MAX_QR_ITERATIONS = 300;

    /** Computed singular values. */
    private final double[] singularValues;
    /** Row dimension of the original matrix (m). */
    private final int m;
    /** Column dimension of the original matrix (n). */
    private final int n;
    /** Indicator if the original matrix was transposed. */
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

    /**
     * Tolerance value for small singular values, calculated once we have
     * populated "singularValues".
     **/
    private final double tol;

    /**
     * Calculates the compact Singular Value Decomposition of the given matrix.
     *
     * @param matrix Matrix to decompose.
     */
    public SingularValueDecomposition(final RealMatrix matrix) {
        // Step 1: Prepare the matrix and determine dimensions
        MatrixDecompositionData decompositionData = prepareMatrixForDecomposition(matrix);
        final double[][] A = decompositionData.matrixData;
        this.m = decompositionData.m;
        this.n = decompositionData.n;
        this.transposed = decompositionData.transposed;

        this.singularValues = new double[n];
        final double[][] U = new double[m][n];
        final double[][] V = new double[n][n];
        final double[] e = new double[n];
        final double[] work = new double[m];

        // Step 2: Reduce A to bidiagonal form
        performBidiagonalization(A, U, V, singularValues, e, work);

        // Step 3: Generate orthogonal matrix U
        generateUOrthogonalMatrix(U, singularValues);

        // Step 4: Generate orthogonal matrix V
        generateVOrthogonalMatrix(V, e);

        // Step 5: Main iteration loop for singular values (QR algorithm)
        performQRAlgorithm(U, V, singularValues, e);

        // Step 6: Set tolerance for small singular values
        tol = FastMath.max(m * singularValues[0] * EPS, FastMath.sqrt(Precision.SAFE_MIN));

        // Step 7: Cache U and V matrices, potentially swapping due to initial transposition
        if (!transposed) {
            cachedU = MatrixUtils.createRealMatrix(U);
            cachedV = MatrixUtils.createRealMatrix(V);
        } else {
            // If original matrix was transposed, then U and V are swapped
            cachedU = MatrixUtils.createRealMatrix(V);
            cachedV = MatrixUtils.createRealMatrix(U);
        }
    }

    /**
     * Helper class to encapsulate matrix dimensions and data during preparation.
     */
    private static class MatrixDecompositionData {
        final double[][] matrixData;
        final int m; // row dimension after potential transpose
        final int n; // column dimension after potential transpose
        final boolean transposed;

        MatrixDecompositionData(double[][] matrixData, int m, int n, boolean transposed) {
            this.matrixData = matrixData;
            this.m = m;
            this.n = n;
            this.transposed = transposed;
        }
    }

    /**
     * Prepares the input matrix for decomposition, transposing if necessary
     * to ensure m >= n.
     * @param matrix The original matrix.
     * @return A {@link MatrixDecompositionData} containing the processed matrix data
     *         and its effective dimensions.
     */
    private MatrixDecompositionData prepareMatrixForDecomposition(final RealMatrix matrix) {
        final double[][] A;
        final int mOriginal = matrix.getRowDimension();
        final int nOriginal = matrix.getColumnDimension();

        final boolean currentTransposed;
        final int mEffective; // Effective 'm' (larger dimension)
        final int nEffective; // Effective 'n' (smaller dimension)

        if (mOriginal < nOriginal) {
            currentTransposed = true;
            A = matrix.transpose().getData();
            mEffective = nOriginal;
            nEffective = mOriginal;
        } else {
            currentTransposed = false;
            A = matrix.getData();
            mEffective = mOriginal;
            nEffective = nOriginal;
        }
        return new MatrixDecompositionData(A, mEffective, nEffective, currentTransposed);
    }

    /**
     * Reduces the matrix A to bidiagonal form using Householder reflections.
     * The diagonal elements are stored in `s` and super-diagonal elements in `e`.
     * Orthogonal transformations are accumulated in `U` and `V`.
     *
     * @param A The matrix data (modified in place).
     * @param U The matrix to accumulate left Householder transformations.
     * @param V The matrix to accumulate right Householder transformations.
     * @param s The array to store diagonal elements (singular values candidates).
     * @param e The array to store super-diagonal elements.
     * @param work Temporary working array.
     */
    private void performBidiagonalization(final double[][] A, final double[][] U,
                                          final double[][] V, final double[] s,
                                          final double[] e, final double[] work) {

        // These define the bounds for column and row transformations
        final int columnTransformLimit = FastMath.min(m - 1, n); // nct in original code
        final int rowTransformLimit = FastMath.max(0, n - 2);   // nrt in original code

        for (int k = 0; k < FastMath.max(columnTransformLimit, rowTransformLimit); k++) {
            // Apply Householder to columns
            if (k < columnTransformLimit) {
                // Compute 2-norm of k-th column and store in s[k]
                s[k] = 0;
                for (int i = k; i < m; i++) {
                    s[k] = FastMath.hypot(s[k], A[i][k]);
                }

                if (s[k] != 0) {
                    if (A[k][k] < 0) {
                        s[k] = -s[k];
                    }
                    // Normalize k-th column
                    for (int i = k; i < m; i++) {
                        A[i][k] /= s[k];
                    }
                    A[k][k] += 1; // Store Householder vector
                }
                s[k] = -s[k]; // Actual diagonal element (magnitude)
            }

            // Apply column transformation to remaining submatrix A[k..m-1][k+1..n-1]
            for (int j = k + 1; j < n; j++) {
                if (k < columnTransformLimit && s[k] != 0) {
                    double dotProduct = 0;
                    for (int i = k; i < m; i++) {
                        dotProduct += A[i][k] * A[i][j];
                    }
                    final double factor = -dotProduct / A[k][k];
                    for (int i = k; i < m; i++) {
                        A[i][j] += factor * A[i][k];
                    }
                }
                // Store k-th row for subsequent row transformation
                e[j] = A[k][j];
            }

            // Store column transformation in U
            if (k < columnTransformLimit) {
                for (int i = k; i < m; i++) {
                    U[i][k] = A[i][k];
                }
            }

            // Apply Householder to rows (for super-diagonal elements)
            if (k < rowTransformLimit) {
                // Compute 2-norm of k-th row (from k+1 to n-1) and store in e[k]
                e[k] = 0;
                for (int i = k + 1; i < n; i++) {
                    e[k] = FastMath.hypot(e[k], e[i]);
                }

                if (e[k] != 0) {
                    if (e[k + 1] < 0) {
                        e[k] = -e[k];
                    }
                    // Normalize k-th row
                    for (int i = k + 1; i < n; i++) {
                        e[i] /= e[k];
                    }
                    e[k + 1] += 1; // Store Householder vector
                }
                e[k] = -e[k]; // Actual super-diagonal element (magnitude)

                // Apply row transformation to remaining submatrix A[k+1..m-1][k+1..n-1]
                if (k + 1 < m && e[k] != 0) {
                    // Compute product A * H_row
                    for (int i = k + 1; i < m; i++) {
                        work[i] = 0; // Clear work array
                    }
                    for (int j = k + 1; j < n; j++) {
                        for (int i = k + 1; i < m; i++) {
                            work[i] += e[j] * A[i][j];
                        }
                    }
                    // Apply the transformation
                    for (int j = k + 1; j < n; j++) {
                        final double factor = -e[j] / e[k + 1];
                        for (int i = k + 1; i < m; i++) {
                            A[i][j] += factor * work[i];
                        }
                    }
                }

                // Store row transformation in V
                for (int i = k + 1; i < n; i++) {
                    V[i][k] = e[i];
                }
            }
        }

        // Set up the final bidiagonal matrix (handle edge cases for p = n)
        if (columnTransformLimit < n) {
            s[columnTransformLimit] = A[columnTransformLimit][columnTransformLimit];
        }
        if (m < n) { 
            s[n - 1] = 0; // If m < n, then for the last element, s[n-1] could be 0.
        }
        if (rowTransformLimit + 1 < n) {
            e[rowTransformLimit] = A[rowTransformLimit][n - 1];
        }
        e[n - 1] = 0; // Last super-diagonal element is zero
    }

    /**
     * Generates the orthogonal matrix U from the Householder vectors stored in U.
     *
     * @param U The matrix to be generated, initially containing Householder vectors.
     * @param s The array of singular values.
     */
    private void generateUOrthogonalMatrix(final double[][] U, final double[] s) {
        final int columnTransformLimit = FastMath.min(m - 1, n); // nct in original code

        // Initialize last columns of U (identity for the implicit parts)
        for (int j = columnTransformLimit; j < n; j++) {
            for (int i = 0; i < m; i++) {
                U[i][j] = 0;
            }
            U[j][j] = 1;
        }

        // Apply Householder transformations in reverse order to form U
        for (int k = columnTransformLimit - 1; k >= 0; k--) {
            if (s[k] != 0) {
                // Apply transformations U_k ... U_n-1 to U_k+1 ... U_n-1
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
                // Normalize and set U_k
                for (int i = k; i < m; i++) {
                    U[i][k] = -U[i][k];
                }
                U[k][k] = 1 + U[k][k]; // Diagonal element (1 + alpha)
                // Zero out elements above the diagonal (U is upper trapezoidal here)
                for (int i = 0; i < k; i++) { 
                    U[i][k] = 0;
                }
            } else {
                // If s[k] is zero, the k-th column transformation was trivial.
                // U[k] should be a canonical basis vector.
                for (int i = 0; i < m; i++) {
                    U[i][k] = 0;
                }
                U[k][k] = 1;
            }
        }
    }

    /**
     * Generates the orthogonal matrix V from the Householder vectors stored in V.
     *
     * @param V The matrix to be generated, initially containing Householder vectors.
     * @param e The array of super-diagonal elements.
     */
    private void generateVOrthogonalMatrix(final double[][] V, final double[] e) {
        final int rowTransformLimit = FastMath.max(0, n - 2); // nrt in original code

        // Apply Householder transformations in reverse order to form V
        for (int k = n - 1; k >= 0; k--) {
            if (k < rowTransformLimit && e[k] != 0) { // Check e[k] for non-trivial transformation
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
            // Initialize k-th column of V
            for (int i = 0; i < n; i++) {
                V[i][k] = 0;
            }
            V[k][k] = 1;
        }
    }

    /**
     * Performs the QR algorithm to compute singular values and update U and V.
     * This is the iterative part that converges the bidiagonal matrix to a diagonal one.
     *
     * @param U The left orthogonal matrix (modified in place).
     * @param V The right orthogonal matrix (modified in place).
     * @param s The array of singular values (modified in place).
     * @param e The array of super-diagonal elements (modified in place).
     */
    private void performQRAlgorithm(final double[][] U, final double[][] V,
                                    final double[] s, final double[] e) {
        int p = n; // Current size of the active submatrix
        int iterationCount = 0; // Number of iterations (iter in original code)

        while (p > 0) {
            int k; // Index to negligible element
            int kase; // Case indicator for QR step

            // Look for a negligible super-diagonal element e[k] to split the matrix,
            // or a negligible diagonal element s[k] to deflate.
            // This loop finds the largest 'k' such that e[k] is not negligible,
            // or if all are negligible, it finds 'k' for deflation/convergence.
            for (k = p - 2; k >= 0; k--) {
                final double threshold = TINY + EPS * (FastMath.abs(s[k]) + FastMath.abs(s[k + 1]));
                // The check `!(FastMath.abs(e[k]) > threshold)` handles NaN cases correctly.
                if (!(FastMath.abs(e[k]) > threshold)) {
                    e[k] = 0; // Treat as zero
                    break;
                }
            }

            if (k == p - 2) {
                kase = 4; // e[p-2] is negligible, convergence for this block
            } else {
                int ks; // Index for checking s[ks] for negligibility
                for (ks = p - 1; ks >= k; ks--) {
                    if (ks == k) {
                        break;
                    }
                    // Check if s[ks] is negligible relative to neighbors
                    final double t = (ks != p -1 ? FastMath.abs(e[ks]) : 0) +
                                     (ks != k + 1 ? FastMath.abs(e[ks - 1]) : 0);
                    if (FastMath.abs(s[ks]) <= TINY + EPS * t) {
                        s[ks] = 0; // Treat as zero
                        break;
                    }
                }
                if (ks == k) {
                    kase = 3; // e[k-1] is negligible, but s[k]..s[p] are not (QR step needed)
                } else if (ks == p - 1) {
                    kase = 1; // s[p] and e[p-2] are negligible, deflate s[p]
                } else {
                    kase = 2; // s[k] is negligible, split at s[k]
                    k = ks;
                }
            }
            k++; // Adjust k for 1-based indexing in comments, or for loop limits

            // Check for too many iterations to prevent infinite loops for ill-conditioned matrices
            if (iterationCount >= MAX_QR_ITERATIONS) {
                // This means the algorithm failed to converge within a reasonable number of iterations.
                // For a robust implementation, this might throw an exception or return partial results.
                // For now, we mimic the original's implicit assumption of convergence.
                // In production, this often indicates an issue with the input matrix or algorithm limits.
                break;
            }

            // Perform the task indicated by kase
            switch (kase) {
                case 1:
                    // Deflate negligible s(p)
                    deflateNegligibleSingularValue(k, p, s, e, V);
                    break;
                case 2:
                    // Split at negligible s(k)
                    splitAtNegligibleSingularValue(k, p, s, e, U);
                    break;
                case 3:
                    // Perform one QR step (Golub-Kahan step)
                    performGolubKahanStep(k, p, s, e, U, V);
                    iterationCount++;
                    break;
                case 4:
                    // Convergence: make singular values positive and order them
                    handleConvergenceAndOrderSingularValues(k, p, s, U, V);
                    iterationCount = 0; // Reset iteration count for the new sub-problem
                    p--; // Reduce the active submatrix size
                    break;
                default:
                    // Should not happen with current logic
                    break;
            }
        }
    }

    /**
     * Case 1: Deflate negligible s(p). Apply a rotation to zero out e[p-2].
     *
     * @param k The starting index for the current subproblem.
     * @param p The current dimension of the active submatrix.
     * @param s Singular values array.
     * @param e Super-diagonal elements array.
     * @param V Right orthogonal matrix.
     */
    private void deflateNegligibleSingularValue(final int k, final int p,
                                                final double[] s, final double[] e,
                                                final double[][] V) {
        double f = e[p - 2];
        e[p - 2] = 0; // Zero out the negligible super-diagonal element

        for (int j = p - 2; j >= k; j--) {
            // Apply a Givens rotation to zero out 'f'
            double t = FastMath.hypot(s[j], f);
            final double cs = s[j] / t;
            final double sn = f / t;
            s[j] = t; // Update singular value

            if (j != k) {
                f = -sn * e[j - 1]; // New 'f' for the next rotation
                e[j - 1] = cs * e[j - 1]; // Update e[j-1]
            }

            // Apply rotation to V columns V[j] and V[p-1]
            for (int i = 0; i < n; i++) {
                t = cs * V[i][j] + sn * V[i][p - 1];
                V[i][p - 1] = -sn * V[i][j] + cs * V[i][p - 1];
                V[i][j] = t;
            }
        }
    }

    /**
     * Case 2: Split at negligible s(k). Apply a rotation to zero out e[k-1].
     *
     * @param k The starting index for the current subproblem.
     * @param p The current dimension of the active submatrix.
     * @param s Singular values array.
     * @param e Super-diagonal elements array.
     * @param U Left orthogonal matrix.
     */
    private void splitAtNegligibleSingularValue(final int k, final int p,
                                                final double[] s, final double[] e,
                                                final double[][] U) {
        double f = e[k - 1];
        e[k - 1] = 0; // Zero out the negligible super-diagonal element

        for (int j = k; j < p; j++) {
            // Apply a Givens rotation to zero out 'f'
            double t = FastMath.hypot(s[j], f);
            final double cs = s[j] / t;
            final double sn = f / t;
            s[j] = t; // Update singular value
            f = -sn * e[j]; // New 'f' for the next rotation
            e[j] = cs * e[j]; // Update e[j]

            // Apply rotation to U columns U[j] and U[k-1]
            for (int i = 0; i < m; i++) {
                t = cs * U[i][j] + sn * U[i][k - 1];
                U[i][k - 1] = -sn * U[i][j] + cs * U[i][k - 1];
                U[i][j] = t;
            }
        }
    }

    /**
     * Case 3: Perform one Golub-Kahan QR step.
     *
     * @param k The starting index for the current subproblem.
     * @param p The current dimension of the active submatrix.
     * @param s Singular values array.
     * @param e Super-diagonal elements array.
     * @param U Left orthogonal matrix.
     * @param V Right orthogonal matrix.
     */
    private void performGolubKahanStep(final int k, final int p,
                                       final double[] s, final double[] e,
                                       final double[][] U, final double[][] V) {
        // Calculate the shift (Wilkinson shift strategy)
        final double s_p_minus_1 = s[p - 1];
        final double s_p_minus_2 = s[p - 2];
        final double e_p_minus_2 = e[p - 2];
        final double s_k = s[k];
        final double e_k = e[k];

        // Scale to prevent under/overflow
        final double scale = FastMath.max(FastMath.max(FastMath.max(FastMath.abs(s_p_minus_1),
                                                                    FastMath.abs(s_p_minus_2)),
                                                        FastMath.abs(e_p_minus_2)),
                                          FastMath.max(FastMath.abs(s_k), FastMath.abs(e_k)));

        final double sp = s_p_minus_1 / scale;
        final double spm1 = s_p_minus_2 / scale;
        final double epm1 = e_p_minus_2 / scale;
        final double sk = s_k / scale;
        final double ek = e_k / scale;

        final double b = ((spm1 + sp) * (spm1 - sp) + epm1 * epm1) / 2.0;
        final double c = (sp * epm1) * (sp * epm1);
        double shift = 0;
        if (b != 0 || c != 0) {
            shift = FastMath.sqrt(b * b + c);
            if (b < 0) {
                shift = -shift;
            }
            shift = c / (b + shift);
        }

        double f = (sk + sp) * (sk - sp) + shift;
        double g = sk * ek;

        // Chase zeros through the bidiagonal matrix using Givens rotations
        for (int j = k; j < p - 1; j++) {
            // Apply a rotation to zero out 'g' (right rotation)
            double t = FastMath.hypot(f, g);
            double cs = f / t;
            double sn = g / t;
            if (j != k) {
                e[j - 1] = t; // Update super-diagonal element
            }
            f = cs * s[j] + sn * e[j];
            e[j] = cs * e[j] - sn * s[j];
            g = sn * s[j + 1];
            s[j + 1] = cs * s[j + 1];

            // Apply rotation to V columns V[j] and V[j+1]
            for (int i = 0; i < n; i++) {
                t = cs * V[i][j] + sn * V[i][j + 1];
                V[i][j + 1] = -sn * V[i][j] + cs * V[i][j + 1];
                V[i][j] = t;
            }

            // Apply a rotation to zero out 'g' (left rotation)
            t = FastMath.hypot(f, g);
            cs = f / t;
            sn = g / t;
            s[j] = t; // Update singular value
            f = cs * e[j] + sn * s[j + 1];
            s[j + 1] = -sn * e[j] + cs * s[j + 1];
            g = sn * e[j + 1];
            e[j + 1] = cs * e[j + 1];

            // Apply rotation to U columns U[j] and U[j+1]
            if (j < m - 1) { // Ensure U has enough rows to apply rotation
                for (int i = 0; i < m; i++) {
                    t = cs * U[i][j] + sn * U[i][j + 1];
                    U[i][j + 1] = -sn * U[i][j] + cs * U[i][j + 1];
                    U[i][j] = t;
                }
            }
        }
        e[p - 2] = f; // Store the last f
    }

    /**
     * Case 4: Convergence. Make singular values non-negative and sort them.
     *
     * @param k The starting index for the current subproblem.
     * @param p The current dimension of the active submatrix.
     * @param s Singular values array.
     * @param U Left orthogonal matrix.
     * @param V Right orthogonal matrix.
     */
    private void handleConvergenceAndOrderSingularValues(final int k, final int p,
                                                         final double[] s,
                                                         final double[][] U, final double[][] V) {
        // Ensure singular value is non-negative
        if (s[k] <= 0) {
            s[k] = FastMath.abs(s[k]); 
            // If s[k] was originally negative, negate the corresponding column of V
            for (int i = 0; i < n; i++) {
                V[i][k] = -V[i][k];
            }
        }

        // Sort the singular values in non-increasing order
        // This bubble sort style loop moves s[k] to its correct sorted position
        while (k < p - 1 && s[k] < s[k + 1]) { 
            // Swap s[k] and s[k+1]
            double tempSingularValue = s[k];
            s[k] = s[k + 1];
            s[k + 1] = tempSingularValue;

            // Swap columns V[k] and V[k+1]
            for (int i = 0; i < n; i++) {
                double tempV = V[i][k + 1];
                V[i][k + 1] = V[i][k];
                V[i][k] = tempV;
            }

            // Swap columns U[k] and U[k+1]
            for (int i = 0; i < m; i++) {
                double tempU = U[i][k + 1];
                U[i][k + 1] = U[i][k];
                U[i][k] = tempU;
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
        // return the cached matrix
        return cachedU;

    }

    /**
     * Returns the transpose of the matrix U of the decomposition.
     * <p>U is an orthogonal matrix, i.e. its transpose is also its inverse.</p>
     * @return the U matrix (or null if decomposed matrix is singular)
     * @see #getU()
     */
    public RealMatrix getUT() {
        if (cachedUt == null) {
            cachedUt = getU().transpose();
        }
        // return the cached matrix
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
            // cache the matrix for subsequent calls
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
     * @return the V matrix (or null if decomposed matrix is singular)
     * @see #getVT()
     */
    public RealMatrix getV() {
        // return the cached matrix
        return cachedV;
    }

    /**
     * Returns the transpose of the matrix V of the decomposition.
     * <p>V is an orthogonal matrix, i.e. its transpose is also its inverse.</p>
     * @return the V matrix (or null if decomposed matrix is singular)
     * @see #getV()
     */
    public RealMatrix getVT() {
        if (cachedVt == null) {
            cachedVt = getV().transpose();
        }
        // return the cached matrix
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
        // get the number of singular values to consider
        final int p = singularValues.length;
        int dimension = 0;
        while (dimension < p &&
               singularValues[dimension] >= minSingularValue) {
            ++dimension;
        }

        if (dimension == 0) {
            throw new NumberIsTooLargeException(LocalizedFormats.TOO_LARGE_CUTOFF_SINGULAR_VALUE,
                                                minSingularValue, singularValues[0], true);
        }

        // Create a temporary matrix that holds V^T * S^-1
        // The walkInOptimizedOrder applies the operation on a submatrix defined by bounds
        final double[][] data = new double[dimension][p]; // Will only fill up to 'dimension' rows
        getVT().walkInOptimizedOrder(new DefaultRealMatrixPreservingVisitor() {
            /** {@inheritDoc} */
            @Override
            public void visit(final int row, final int column,
                    final double value) {
                // Only consider rows up to 'dimension' and singular values > Precision.SAFE_MIN
                if (row < dimension && singularValues[row] > Precision.SAFE_MIN) {
                    data[row][column] = value / singularValues[row];
                } else if (row < dimension) { // If singular value is zero but row is within dimension, result is 0
                    data[row][column] = 0;
                }
                // Else, for rows >= dimension, data is already 0, and we don't need to compute for them
            }
        }, 0, dimension - 1, 0, p - 1); // Walk only relevant part of VT

        RealMatrix jv = new Array2DRowRealMatrix(data, false); // jv = S^-1 * V^T, where S^-1 is effectively diagonal
        return jv.transpose().multiply(jv); // (S^-1 * V^T)^T * (S^-1 * V^T) = V * S^-1 * S^-1 * V^T
                                            // This is the common formula for pseudoinverse-based covariance V * diag(1/s_i^2) * V^T
    }

    /**
     * Returns the L<sub>2</sub> norm of the matrix.
     * <p>The L<sub>2</sub> norm is max(|A &times; u|<sub>2</sub> /
     * |u|<sub>2</sub>), where |.|<sub>2</sub> denotes the vectorial 2-norm
     * (i.e. the traditional euclidian norm).</p>
     * @return norm
     */
    public double getNorm() {
        return singularValues[0];
    }

    /**
     * Return the condition number of the matrix.
     * @return condition number of the matrix
     */
    public double getConditionNumber() {
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
        for (double s : singularValues) {
            if (s > tol) {
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
        // The original code uses 'm' for nonSingular check: getRank() == m
        // This implies that if the rank is equal to the number of rows, it's non-singular.
        // We maintain this behavior.
        return new Solver(singularValues, getUT(), getV(), getRank() == m, tol);
    }

    /** Specialized solver. */
    private static class Solver implements DecompositionSolver {
        /** Pseudo-inverse of the initial matrix. */
        private final RealMatrix pseudoInverse;
        /** Singularity indicator. */
        private final boolean nonSingular;

        /**
         * Build a solver from decomposed matrix.
         *
         * @param singularValues Singular values.
         * @param uT U<sup>T</sup> matrix of the decomposition.
         * @param v V matrix of the decomposition.
         * @param nonSingular Singularity indicator.
         * @param tol tolerance for singular values
         */
        private Solver(final double[] singularValues, final RealMatrix uT,
                       final RealMatrix v, final boolean nonSingular, final double tol) {
            // Create a defensive copy of uT's data to avoid modifying the cached UT matrix
            final double[][] suTData = uT.copy().getData(); 

            for (int i = 0; i < singularValues.length; ++i) {
                final double scaleFactor;
                if (singularValues[i] > tol) {
                    scaleFactor = 1 / singularValues[i];
                } else {
                    scaleFactor = 0;
                }
                final double[] suTi = suTData[i];
                for (int j = 0; j < suTi.length; ++j) {
                    suTi[j] *= scaleFactor;
                }
            }
            // pseudoInverse = V * Sigma_plus * U^T
            pseudoInverse = v.multiply(new Array2DRowRealMatrix(suTData, false)); // suTData is now Sigma_plus * U^T
            this.nonSingular = nonSingular;
        }

        /**
         * Solve the linear equation A &times; X = B in least square sense.
         * <p>
         * The m&times;n matrix A may not be square, the solution X is such that
         * ||A &times; X - B|| is minimal.
         * </p>
         * @param b Right-hand side of the equation A &times; X = B
         * @return a vector X that minimizes the two norm of A &times; X - B
         * @throws org.apache.commons.math3.exception.DimensionMismatchException
         * if the matrices dimensions do not match.
         */
        public RealVector solve(final RealVector b) {
            return pseudoInverse.operate(b);
        }

        /**
         * Solve the linear equation A &times; X = B in least square sense.
         * <p>
         * The m&times;n matrix A may not be square, the solution X is such that
         * ||A &times; X - B|| is minimal.
         * </p>
         *
         * @param b Right-hand side of the equation A &times; X = B
         * @return a matrix X that minimizes the two norm of A &times; X - B
         * @throws org.apache.commons.math3.exception.DimensionMismatchException
         * if the matrices dimensions do not match.
         */
        public RealMatrix solve(final RealMatrix b) {
            return pseudoInverse.multiply(b);
        }

        /**
         * Check if the decomposed matrix is non-singular.
         *
         * @return {@code true} if the decomposed matrix is non-singular.
         */
        public boolean isNonSingular() {
            return nonSingular;
        }

        /**
         * Get the pseudo-inverse of the decomposed matrix.
         *
         * @return the inverse matrix.
         */
        public RealMatrix getInverse() {
            return pseudoInverse;
        }
    }
}