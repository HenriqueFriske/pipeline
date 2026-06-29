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
    /** Computed singular values. */
    private final double[] singularValues;
    /** max(row dimension, column dimension). */
    private final int m;
    /** min(row dimension, column dimension). */
    private final int n;
    /** Indicator for transposed matrix. */
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
     * populated "singularValues". This is like knowing the 'acceptable' tiny air bubbles
     * in a well-baked bread, anything smaller than this doesn't count.
     */
    private final double tol;

    /** Temporary array used during bidiagonalization for row transformations. */
    private final double[] e;
    /** Temporary array used during bidiagonalization for column transformations. */
    private final double[] work;
    /** The U matrix as a 2D array, built during decomposition. */
    private final double[][] U;
    /** The V matrix as a 2D array, built during decomposition. */
    private final double[][] V;

    /**
     * Calculates the compact Singular Value Decomposition of the given matrix.
     * <p>
     * This is like baking a sourdough loaf. We prepare the dough, then put it through
     * several shaping and proofing stages, and finally bake it to perfection.
     * </p>
     *
     * @param matrix Matrix to decompose. This is our initial 'dough'.
     */
    public SingularValueDecomposition(final RealMatrix matrix) {
        // Step 1: Prepare the initial matrix and set up dimensions. Like preparing the starter.
        final double[][] A = initializeMatrixAndDimensions(matrix);

        // Initialize temporary arrays and matrices for the decomposition process.
        // These are like our mixing bowls, proofing baskets, and baking sheets.
        this.singularValues = new double[n];
        this.U = new double[m][n];
        this.V = new double[n][n];
        this.e = new double[n];
        this.work = new double[m];

        // Step 2: Reduce the matrix to bidiagonal form. This is like the bulk fermentation
        // and initial shaping of the dough. We're getting it into a more manageable form.
        reduceToBidiagonalForm(A);

        // Step 3: Set up the final bidiagonal matrix for the QR iteration.
        // A small adjustment before the final bake.
        setupFinalBidiagonalMatrix();

        // Step 4: Generate the U and V matrices. These are like the oven setup and specific baking tools
        // needed for the final structure of our bread.
        generateUmatrix();
        generateVmatrix();

        // Step 5: The main iteration loop for singular values (QR iteration).
        // This is the core 'baking' process, where we refine the singular values
        // until they are stable, like the precise temperature control during baking.
        performQRiteration();

        // Step 6: Post-processing: Calculate tolerance and cache final matrices.
        // This is like checking the bread for doneness and storing it properly.
        this.tol = calculateSingularValueTolerance();
        assignCachedMatrices();
    }

    /**
     * Initializes the input matrix `A` and sets up the dimensions `m` and `n`,
     * handling transposition if the matrix is 'wide' (more columns than rows).
     * This is like deciding if we're working with a short, wide loaf or a tall, thin one
     * and perhaps flipping it to make it easier to handle.
     * @param matrix The original matrix to decompose.
     * @return The (potentially transposed) 2D array representation of the matrix.
     */
    private double[][] initializeMatrixAndDimensions(final RealMatrix matrix) {
        final double[][] AData;
        if (matrix.getRowDimension() < matrix.getColumnDimension()) {
            transposed = true;
            AData = matrix.transpose().getData();
            m = matrix.getColumnDimension(); // m becomes original column dimension
            n = matrix.getRowDimension();    // n becomes original row dimension
        } else {
            transposed = false;
            AData = matrix.getData();
            m = matrix.getRowDimension();
            n = matrix.getColumnDimension();
        }
        return AData;
    }

    /**
     * Reduces the given matrix A to bidiagonal form. This involves applying a series
     * of Householder transformations to both columns and rows. It's like a careful
     * series of folds and turns to shape the dough into a specific preliminary form.
     * The diagonal elements are stored in `singularValues`, and super-diagonal elements in `e`.
     * @param A The matrix data to be bidiagonalized (modified in place).
     */
    private void reduceToBidiagonalForm(final double[][] A) {
        // These are thresholds for how many columns/rows need transforming.
        // Like knowing how many 'sections' of the dough need special attention.
        final int numColumnsToTransform = FastMath.min(m - 1, n);
        final int numRowsToTransform = FastMath.max(0, n - 2);

        for (int k = 0; k < FastMath.max(numColumnsToTransform, numRowsToTransform); k++) {
            // First, apply transformation to the k-th column.
            // This is like making a vertical score in the dough.
            if (k < numColumnsToTransform) {
                applyColumnTransformation(A, k);
            }

            // Apply transformation based on the k-th column to subsequent columns.
            // This spreads the 'effect' of the vertical score across the loaf.
            applyColumnInducedTransformationToRemainingColumns(A, k, numColumnsToTransform);

            // Now, apply transformation to the k-th row (super-diagonal element).
            // This is like making a horizontal score.
            if (k < numRowsToTransform) {
                applyRowTransformation(A, k, numRowsToTransform);

                // Apply transformation based on the k-th row to subsequent rows.
                // This spreads the 'effect' of the horizontal score.
                applyRowInducedTransformationToRemainingRows(A, k, numRowsToTransform);
            }
        }
    }

    /**
     * Applies a Householder transformation to the k-th column of matrix A,
     * storing the k-th diagonal element in `singularValues[k]` and the transformation
     * vector in `U[i][k]`. This is one specific type of 'fold' for a column.
     * @param A The matrix data (modified in place).
     * @param k The current column index.
     */
    private void applyColumnTransformation(final double[][] A, final int k) {
        singularValues[k] = 0;
        for (int i = k; i < m; i++) {
            singularValues[k] = FastMath.hypot(singularValues[k], A[i][k]);
        }

        if (singularValues[k] != 0) {
            if (A[k][k] < 0) {
                singularValues[k] = -singularValues[k];
            }
            for (int i = k; i < m; i++) {
                A[i][k] /= singularValues[k];
            }
            A[k][k] += 1;
        }
        singularValues[k] = -singularValues[k];

        // Place the transformation in U for subsequent back multiplication.
        for (int i = k; i < m; i++) {
            U[i][k] = A[i][k];
        }
    }

    /**
     * Applies the transformation derived from column k to all subsequent columns (j > k).
     * This ensures the bidiagonal form is maintained. It's like ensuring all parts of the dough
     * get the benefit of the previous fold.
     * @param A The matrix data (modified in place).
     * @param k The current column index.
     * @param numColumnsToTransform The maximum column index for transformations.
     */
    private void applyColumnInducedTransformationToRemainingColumns(final double[][] A, final int k, final int numColumnsToTransform) {
        for (int j = k + 1; j < n; j++) {
            if (k < numColumnsToTransform && singularValues[k] != 0) {
                double t = 0;
                for (int i = k; i < m; i++) {
                    t += A[i][k] * A[i][j];
                }
                t = -t / A[k][k];
                for (int i = k; i < m; i++) {
                    A[i][j] += t * A[i][k];
                }
            }
            // Place the k-th row of A into e for the subsequent row transformation.
            e[j] = A[k][j];
        }
    }

    /**
     * Applies a Householder transformation to the k-th row of matrix A (from `e` array),
     * storing the k-th super-diagonal element in `e[k]` and the transformation vector
     * in `V[i][k]`. This is one specific type of 'fold' for a row.
     * @param A The matrix data (modified in place).
     * @param k The current row index.
     * @param numRowsToTransform The maximum row index for transformations.
     */
    private void applyRowTransformation(final double[][] A, final int k, final int numRowsToTransform) {
        e[k] = 0;
        for (int i = k + 1; i < n; i++) {
            e[k] = FastMath.hypot(e[k], e[i]);
        }

        if (e[k] != 0) {
            if (e[k + 1] < 0) {
                e[k] = -e[k];
            }
            for (int i = k + 1; i < n; i++) {
                e[i] /= e[k];
            }
            e[k + 1] += 1;
        }
        e[k] = -e[k];

        // Place the transformation in V for subsequent back multiplication.
        for (int i = k + 1; i < n; i++) {
            V[i][k] = e[i];
        }
    }

    /**
     * Applies the transformation derived from row k to all subsequent rows (i > k).
     * This ensures the bidiagonal form is maintained. It's another way to ensure all parts
     * of the dough receive the proper 'shaping'.
     * @param A The matrix data (modified in place).
     * @param k The current row index.
     * @param numRowsToTransform The maximum row index for transformations.
     */
    private void applyRowInducedTransformationToRemainingRows(final double[][] A, final int k, final int numRowsToTransform) {
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
                final double t = -e[j] / e[k + 1];
                for (int i = k + 1; i < m; i++) {
                    A[i][j] += t * work[i];
                }
            }
        }
    }

    /**
     * Sets up the final bidiagonal matrix, essentially tidying up the diagonal
     * and super-diagonal elements after the initial reduction process.
     * This is like the final gentle shaping before the dough goes into the proofing basket.
     */
    private void setupFinalBidiagonalMatrix() {
        final int p = n; // 'p' denotes the current effective size of the matrix, initially 'n'
        final int numColumnsToTransform = FastMath.min(m - 1, n);
        final int numRowsToTransform = FastMath.max(0, n - 2);

        if (numColumnsToTransform < n) {
            singularValues[numColumnsToTransform] = U[numColumnsToTransform][numColumnsToTransform];
        }
        if (m < p) {
            singularValues[p - 1] = 0;
        }
        if (numRowsToTransform + 1 < p) {
            e[numRowsToTransform] = U[numRowsToTransform][p - 1];
        }
        e[p - 1] = 0;
    }

    /**
     * Generates the U matrix from the accumulated Householder transformations.
     * This is like assembling the oven door or baking stone – crucial for the final bake.
     */
    private void generateUmatrix() {
        final int numColumnsToTransform = FastMath.min(m - 1, n);
        for (int j = numColumnsToTransform; j < n; j++) {
            for (int i = 0; i < m; i++) {
                U[i][j] = 0;
            }
            U[j][j] = 1;
        }

        for (int k = numColumnsToTransform - 1; k >= 0; k--) {
            if (singularValues[k] != 0) {
                for (int j = k + 1; j < n; j++) {
                    double t = 0;
                    for (int i = k; i < m; i++) {
                        t += U[i][k] * U[i][j];
                    }
                    t = -t / U[k][k];
                    for (int i = k; i < m; i++) {
                        U[i][j] += t * U[i][k];
                    }
                }
                for (int i = k; i < m; i++) {
                    U[i][k] = -U[i][k];
                }
                U[k][k] = 1 + U[k][k];
                for (int i = 0; i < k - 1; i++) {
                    U[i][k] = 0;
                }
            } else {
                for (int i = 0; i < m; i++) {
                    U[i][k] = 0;
                }
                U[k][k] = 1;
            }
        }
    }

    /**
     * Generates the V matrix from the accumulated Householder transformations.
     * This is like preparing the baking environment, perhaps setting up steam injections.
     */
    private void generateVmatrix() {
        final int numRowsToTransform = FastMath.max(0, n - 2);
        for (int k = n - 1; k >= 0; k--) {
            if (k < numRowsToTransform && e[k] != 0) {
                for (int j = k + 1; j < n; j++) {
                    double t = 0;
                    for (int i = k + 1; i < n; i++) {
                        t += V[i][k] * V[i][j];
                    }
                    t = -t / V[k + 1][k];
                    for (int i = k + 1; i < n; i++) {
                        V[i][j] += t * V[i][k];
                    }
                }
            }
            for (int i = 0; i < n; i++) {
                V[i][k] = 0;
            }
            V[k][k] = 1;
        }
    }

    /**
     * Performs the main QR iteration loop to converge the singular values.
     * This is the heart of the 'baking' process, where singular values are refined
     * until they are stable and ordered, much like how the bread's internal structure
     * forms during baking.
     */
    private void performQRiteration() {
        int p = n; // Current size of the active submatrix for iteration.
        int iterationsCount = 0; // To track how many adjustments we've made, like watching the clock on the oven.

        while (p > 0) {
            // Step 1: Inspect the 'dough' (s and e arrays) for 'negligible' elements.
            // This helps us decide the next baking step: continue, deflate, split, or converge.
            final IterationCase iterationCase = inspectForNegligibleElements(p);

            // Step 2: Apply the appropriate 'baking adjustment' based on the case.
            // Each case is a specific technique for managing the singular values.
            switch (iterationCase.getCaseId()) {
                case 1: // Deflate a negligible singular value.
                    handleDeflation(iterationCase.getK(), p);
                    break;
                case 2: // Split at a negligible singular value.
                    handleSplit(iterationCase.getK(), p);
                    break;
                case 3: // Perform one QR step to refine singular values.
                    performQRStep(iterationCase.getK(), p);
                    iterationsCount++;
                    break;
                case 4: // Convergence: The singular value is stable.
                default:
                    handleConvergenceAndOrdering(iterationCase.getK(), p);
                    iterationsCount = 0; // Reset count as we've converged for this singular value.
                    p--; // Reduce the active submatrix size, as one singular value is finalized.
                    break;
            }
        }
    }

    /**
     * Helper class to hold the result of inspecting negligible elements.
     * This is like a note card telling us what 'situation' the dough is in.
     */
    private static class IterationCase {
        private final int kaseId;
        private final int kIndex;

        IterationCase(int kaseId, int kIndex) {
            this.kaseId = kaseId;
            this.kIndex = kIndex;
        }

        public int getCaseId() { return kaseId; }
        public int getK() { return kIndex; }
    }

    /**
     * Inspects the singular values (s) and super-diagonal elements (e) to determine
     * the next action in the QR iteration. This is like checking the dough for
     * specific signs of readiness for the next stage of baking.
     * @param p The current upper bound of the active submatrix.
     * @return An {@link IterationCase} indicating the type of action to take and the relevant index `k`.
     */
    private IterationCase inspectForNegligibleElements(final int p) {
        int k;
        // Find 'k' where e[k-1] is negligible (or k=0).
        for (k = p - 2; k >= 0; k--) {
            final double threshold = TINY + EPS * (FastMath.abs(singularValues[k]) +
                                                    FastMath.abs(singularValues[k + 1]));
            // Check if e[k] is not greater than the threshold (i.e., it's negligible).
            // This check is formulated to correctly handle NaN values, which are tricky to compare.
            if (!(FastMath.abs(e[k]) > threshold)) {
                e[k] = 0; // Treat as zero if negligible.
                break;
            }
        }

        if (k == p - 2) {
            return new IterationCase(4, k); // Case 4: Convergence
        } else {
            int ks;
            // Find 'ks' where s[ks] is negligible (or ks=k).
            for (ks = p - 1; ks >= k; ks--) {
                if (ks == k) {
                    break;
                }
                final double t = (ks != p ? FastMath.abs(e[ks]) : 0) +
                                 (ks != k + 1 ? FastMath.abs(e[ks - 1]) : 0);
                if (FastMath.abs(singularValues[ks]) <= TINY + EPS * t) {
                    singularValues[ks] = 0;
                    break;
                }
            }
            if (ks == k) {
                return new IterationCase(3, k); // Case 3: QR step
            } else if (ks == p - 1) {
                return new IterationCase(1, k); // Case 1: Deflate s(p)
            } else {
                return new IterationCase(2, ks); // Case 2: Split at s(ks)
            }
        }
    }

    /**
     * Handles Case 1: Deflates a negligible singular value `s(p)`. This involves applying
     * a rotation to make `e[p-2]` zero, effectively isolating `s(p-1)`.
     * It's like removing a small, unwanted piece from the dough so it doesn't affect the rest.
     * @param k The starting index for the deflation process.
     * @param p The current upper bound of the active submatrix.
     */
    private void handleDeflation(final int k, final int p) {
        double f = e[p - 2];
        e[p - 2] = 0;
        for (int j = p - 2; j >= k; j--) {
            double t = FastMath.hypot(singularValues[j], f);
            final double cs = singularValues[j] / t;
            final double sn = f / t;
            singularValues[j] = t;
            if (j != k) {
                f = -sn * e[j - 1];
                e[j - 1] = cs * e[j - 1];
            }
            applyRotationToV(j, p - 1, cs, sn);
        }
    }

    /**
     * Handles Case 2: Splits the matrix at a negligible singular value `s(k)`. This involves applying
     * a rotation to make `e[k-1]` zero, effectively breaking the matrix into two smaller subproblems.
     * This is like dividing a large loaf into smaller, more manageable rolls.
     * @param k The starting index for the splitting process.
     * @param p The current upper bound of the active submatrix.
     */
    private void handleSplit(final int k, final int p) {
        double f = e[k - 1];
        e[k - 1] = 0;
        for (int j = k; j < p; j++) {
            double t = FastMath.hypot(singularValues[j], f);
            final double cs = singularValues[j] / t;
            final double sn = f / t;
            singularValues[j] = t;
            f = -sn * e[j];
            e[j] = cs * e[j];
            applyRotationToU(j, k - 1, cs, sn);
        }
    }

    /**
     * Handles Case 3: Performs one QR step. This involves calculating a shift and then
     * applying a series of Givens rotations to chase zeros, gradually converging the singular values.
     * This is the most complex 'baking technique', involving many small, precise adjustments.
     * @param k The starting index for the QR step.
     * @param p The current upper bound of the active submatrix.
     */
    private void performQRStep(final int k, final int p) {
        // Calculate the shift. This is like adjusting the oven temperature based on current readings.
        final double sp = singularValues[p - 1];
        final double spm1 = singularValues[p - 2];
        final double epm1 = e[p - 2];
        final double sk = singularValues[k];
        final double ek = e[k];

        final double scale = FastMath.max(FastMath.abs(sp), FastMath.abs(spm1));
        final double b = ((spm1 / scale + sp / scale) * (spm1 / scale - sp / scale) + (epm1 / scale) * (epm1 / scale)) / 2.0;
        final double c = (sp / scale * epm1 / scale) * (sp / scale * epm1 / scale);

        double shift = 0;
        if (b != 0 || c != 0) {
            shift = FastMath.sqrt(b * b + c);
            if (b < 0) {
                shift = -shift;
            }
            shift = c / (b + shift);
        }

        double f = (sk / scale + sp / scale) * (sk / scale - sp / scale) + shift;
        double g = sk / scale * ek / scale;

        // Chase zeros. This is a series of precise 'folds' and 'turns' to refine the shape.
        for (int j = k; j < p - 1; j++) {
            // Apply rotation to V
            double t = FastMath.hypot(f, g);
            double cs = f / t;
            double sn = g / t;
            if (j != k) {
                e[j - 1] = t;
            }
            f = cs * singularValues[j] + sn * e[j];
            e[j] = cs * e[j] - sn * singularValues[j];
            g = sn * singularValues[j + 1];
            singularValues[j + 1] = cs * singularValues[j + 1];
            applyRotationToV(j, j + 1, cs, sn);

            // Apply rotation to U
            t = FastMath.hypot(f, g);
            cs = f / t;
            sn = g / t;
            singularValues[j] = t;
            f = cs * e[j] + sn * singularValues[j + 1];
            singularValues[j + 1] = -sn * e[j] + cs * singularValues[j + 1];
            g = sn * e[j + 1];
            e[j + 1] = cs * e[j + 1];
            if (j < m - 1) {
                applyRotationToU(j, j + 1, cs, sn);
            }
        }
        e[p - 2] = f; // The last element of 'e' is updated.
    }

    /**
     * Applies a Givens rotation to two columns of matrix V.
     * This is a specific type of transformation used repeatedly in the QR step.
     * @param col1 Index of the first column.
     * @param col2 Index of the second column.
     * @param cos The cosine component of the rotation.
     * @param sin The sine component of the rotation.
     */
    private void applyRotationToV(final int col1, final int col2, final double cos, final double sin) {
        for (int i = 0; i < n; i++) {
            final double temp = cos * V[i][col1] + sin * V[i][col2];
            V[i][col2] = -sin * V[i][col1] + cos * V[i][col2];
            V[i][col1] = temp;
        }
    }

    /**
     * Applies a Givens rotation to two columns of matrix U.
     * This is a specific type of transformation used repeatedly in the QR step.
     * @param col1 Index of the first column.
     * @param col2 Index of the second column.
     * @param cos The cosine component of the rotation.
     * @param sin The sine component of the rotation.
     */
    private void applyRotationToU(final int col1, final int col2, final double cos, final double sin) {
        for (int i = 0; i < m; i++) {
            final double temp = cos * U[i][col1] + sin * U[i][col2];
            U[i][col2] = -sin * U[i][col1] + cos * U[i][col2];
            U[i][col1] = temp;
        }
    }

    /**
     * Handles Case 4 (and default): Convergence. Once a singular value has converged,
     * we ensure it's positive and then sort it into its correct non-increasing position.
     * This is like ensuring the bread is perfectly golden and then arranging it neatly.
     * @param k The index of the converged singular value.
     * @param p The current upper bound of the active submatrix (before decrementing).
     */
    private void handleConvergenceAndOrdering(final int k, final int p) {
        // Make the singular values positive. A singular value should always be positive.
        if (singularValues[k] <= 0) {
            singularValues[k] = singularValues[k] < 0 ? -singularValues[k] : 0; // Ensure non-negative
            // If singular value was negative, flip the sign of the corresponding column in V.
            for (int i = 0; i < n; i++) {
                V[i][k] = -V[i][k];
            }
        }
        // Order the singular values in non-increasing order. Like arranging loaves by size.
        orderSingularValues(k, p);
    }

    /**
     * Orders the singular values from largest to smallest, performing corresponding swaps
     * in U and V matrices to maintain consistency. This is like sorting loaves by size.
     * @param startIndex The starting index for sorting.
     * @param endIndex The effective end index (exclusive) for sorting.
     */
    private void orderSingularValues(int startIndex, final int endIndex) {
        while (startIndex < endIndex - 1) {
            if (singularValues[startIndex] >= singularValues[startIndex + 1]) {
                break; // Already in order
            }
            // Swap singular values
            double tempS = singularValues[startIndex];
            singularValues[startIndex] = singularValues[startIndex + 1];
            singularValues[startIndex + 1] = tempS;

            // Swap corresponding columns in V
            for (int i = 0; i < n; i++) {
                double tempV = V[i][startIndex + 1];
                V[i][startIndex + 1] = V[i][startIndex];
                V[i][startIndex] = tempV;
            }

            // Swap corresponding columns in U
            for (int i = 0; i < m; i++) {
                double tempU = U[i][startIndex + 1];
                U[i][startIndex + 1] = U[i][startIndex];
                U[i][startIndex] = tempU;
            }
            startIndex++;
        }
    }

    /**
     * Calculates the tolerance value for small singular values, used to determine
     * the numerical rank and pseudo-inverse. This is like setting the minimum size
     * for what we consider a 'valid' hole in our sourdough crumb.
     * @return The calculated tolerance.
     */
    private double calculateSingularValueTolerance() {
        return FastMath.max(m * singularValues[0] * EPS,
                            FastMath.sqrt(Precision.SAFE_MIN));
    }

    /**
     * Assigns the final U and V matrices to the cached fields, taking into account
     * whether the original matrix was transposed. This is like carefully placing
     * the finished loaves on the cooling rack.
     */
    private void assignCachedMatrices() {
        if (!transposed) {
            cachedU = MatrixUtils.createRealMatrix(U);
            cachedV = MatrixUtils.createRealMatrix(V);
        } else {
            // If the original matrix was transposed, U and V are swapped.
            cachedU = MatrixUtils.createRealMatrix(V);
            cachedV = MatrixUtils.createRealMatrix(U);
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

        final double[][] data = new double[dimension][p];
        getVT().walkInOptimizedOrder(new DefaultRealMatrixPreservingVisitor() {
            /** {@inheritDoc} */
            @Override
            public void visit(final int row, final int column,
                    final double value) {
                data[row][column] = value / singularValues[row];
            }
        }, 0, dimension - 1, 0, p - 1);

        RealMatrix jv = new Array2DRowRealMatrix(data, false);
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
        int r = 0;
        for (int i = 0; i < singularValues.length; i++) {
            if (singularValues[i] > tol) {
                r++;
            }
        }
        return r;
    }

    /**
     * Get a solver for finding the A &times; X = B solution in least square sense.
     * @return a solver
     */
    public DecompositionSolver getSolver() {
        return new Solver(singularValues, getUT(), getV(), getRank() == m, tol);
    }

    /** Specialized solver, like a special tool for solving a specific baking problem. */
    private static class Solver implements DecompositionSolver {
        /** Pseudo-inverse of the initial matrix. */
        private final RealMatrix pseudoInverse;
        /** Singularity indicator. */
        private boolean nonSingular;

        /**
         * Build a solver from decomposed matrix. This is like preparing a special
         * mix based on the ingredients we already have.
         *
         * @param singularValues Singular values.
         * @param uT U<sup>T</sup> matrix of the decomposition.
         * @param v V matrix of the decomposition.
         * @param nonSingular Singularity indicator.
         * @param tol tolerance for singular values
         */
        private Solver(final double[] singularValues, final RealMatrix uT,
                       final RealMatrix v, final boolean nonSingular, final double tol) {
            final double[][] suT = uT.getData();
            for (int i = 0; i < singularValues.length; ++i) {
                final double a;
                if (singularValues[i] > tol) {
                    a = 1 / singularValues[i];
                } else {
                    a = 0;
                }
                final double[] suTi = suT[i];
                for (int j = 0; j < suTi.length; ++j) {
                    suTi[j] *= a;
                }
            }
            pseudoInverse = v.multiply(new Array2DRowRealMatrix(suT, false));
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