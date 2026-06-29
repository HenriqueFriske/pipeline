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
package org.apache.commons.math3.analysis.solvers;

import org.apache.commons.math3.util.FastMath;
import org.apache.commons.math3.exception.NumberIsTooLargeException;
import org.apache.commons.math3.exception.NoBracketingException;
import org.apache.commons.math3.exception.TooManyEvaluationsException;

/**
 * This class implements the <a href="http://mathworld.wolfram.com/MullersMethod.html">
 * Muller's Method</a> for root finding of real univariate functions. For
 * reference, see <b>Elementary Numerical Analysis</b>, ISBN 0070124477,
 * chapter 3.
 * <p>
 * Muller's method applies to both real and complex functions, but here we
 * restrict ourselves to real functions.
 * This class differs from {@link MullerSolver} in the way it avoids complex
 * operations.</p>
 * Muller's original method would have function evaluation at complex point.
 * Since our f(x) is real, we have to find ways to avoid that. Bracketing
 * condition is one way to go: by requiring bracketing in every iteration,
 * the newly computed approximation is guaranteed to be real.</p>
 * <p>
 * Normally Muller's method converges quadratically in the vicinity of a
 * zero, however it may be very slow in regions far away from zeros. For
 * example, f(x) = exp(x) - 1, min = -50, max = 100. In such case we use
 * bisection as a safety backup if it performs very poorly.</p>
 * <p>
 * The formulas here use divided differences directly.</p>
 *
 * @version $Id$
 * @since 1.2
 * @see MullerSolver2
 */
public class MullerSolver extends AbstractUnivariateSolver {

    /** Default absolute accuracy. */
    private static final double DEFAULT_ABSOLUTE_ACCURACY = 1e-6;

    /**
     * Construct a solver with default accuracy (1e-6).
     */
    public MullerSolver() {
        this(DEFAULT_ABSOLUTE_ACCURACY);
    }
    /**
     * Construct a solver.
     *
     * @param absoluteAccuracy Absolute accuracy.
     */
    public MullerSolver(double absoluteAccuracy) {
        super(absoluteAccuracy);
    }
    /**
     * Construct a solver.
     *
     * @param relativeAccuracy Relative accuracy.
     * @param absoluteAccuracy Absolute accuracy.
     */
    public MullerSolver(double relativeAccuracy,
                        double absoluteAccuracy) {
        super(relativeAccuracy, absoluteAccuracy);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected double doSolve()
        throws TooManyEvaluationsException,
               NumberIsTooLargeException,
               NoBracketingException {
        final double min = getMin();
        final double max = getMax();
        final double initial = getStartValue();

        final double functionValueAccuracy = getFunctionValueAccuracy();

        verifySequence(min, initial, max);

        // check for zeros before verifying bracketing
        final double fMin = computeObjectiveValue(min);
        if (FastMath.abs(fMin) < functionValueAccuracy) {
            return min;
        }
        final double fMax = computeObjectiveValue(max);
        if (FastMath.abs(fMax) < functionValueAccuracy) {
            return max;
        }
        final double fInitial = computeObjectiveValue(initial);
        if (FastMath.abs(fInitial) <  functionValueAccuracy) {
            return initial;
        }

        verifyBracketing(min, max);

        if (isBracketing(min, initial)) {
            return solve(min, initial, fMin, fInitial);
        } else {
            return solve(initial, max, fInitial, fMax);
        }
    }

    private interface IterationStrategy {
        double nextX(double x0, double x1, double x2, double y0, double y1, double y2);
        boolean isBisecting(double x, double x0, double x1, double x2);
        double[] nextBounds(double x, double x0, double x1, double x2, double y0, double y1, double y2, double y);
    }

    private class MullerIteration implements IterationStrategy {
        public double nextX(double x0, double x1, double x2, double y0, double y1, double y2) {
            final double d01 = (y1 - y0) / (x1 - x0);
            final double d12 = (y2 - y1) / (x2 - x1);
            final double d012 = (d12 - d01) / (x2 - x0);
            final double c1 = d01 + (x1 - x0) * d012;
            final double delta = c1 * c1 - 4 * y1 * d012;
            final double xplus = x1 + (-2.0 * y1) / (c1 + FastMath.sqrt(delta));
            final double xminus = x1 + (-2.0 * y1) / (c1 - FastMath.sqrt(delta));
            return isSequence(x0, xplus, x2) ? xplus : xminus;
        }
        public boolean isBisecting(double x, double x0, double x1, double x2) {
            return (x < x1 && (x1 - x0) > 0.95 * (x2 - x0)) ||
                   (x > x1 && (x2 - x1) > 0.95 * (x2 - x0)) ||
                   (x == x1);
        }
        public double[] nextBounds(double x, double x0, double x1, double x2, double y0, double y1, double y2, double y) {
            double nx0 = x < x1 ? x0 : x1;
            double ny0 = x < x1 ? y0 : y1;
            double nx2 = x > x1 ? x2 : x1;
            double ny2 = x > x1 ? y2 : y1;
            return new double[]{nx0, ny0, nx2, ny2, x, y};
        }
    }

    private double solve(double min, double max,
                         double fMin, double fMax)
        throws TooManyEvaluationsException {
        final double relativeAccuracy = getRelativeAccuracy();
        final double absoluteAccuracy = getAbsoluteAccuracy();
        final double functionValueAccuracy = getFunctionValueAccuracy();

        double x0 = min;
        double y0 = fMin;
        double x2 = max;
        double y2 = fMax;
        double x1 = 0.5 * (x0 + x2);
        double y1 = computeObjectiveValue(x1);

        double oldx = Double.POSITIVE_INFINITY;
        IterationStrategy strategy = new MullerIteration();

        while (true) {
            double x = strategy.nextX(x0, x1, x2, y0, y1, y2);
            double y = computeObjectiveValue(x);

            final double tolerance = FastMath.max(relativeAccuracy * FastMath.abs(x), absoluteAccuracy);
            if (FastMath.abs(x - oldx) <= tolerance || FastMath.abs(y) <= functionValueAccuracy) {
                return x;
            }

            if (!strategy.isBisecting(x, x0, x1, x2)) {
                double[] b = strategy.nextBounds(x, x0, x1, x2, y0, y1, y2, y);
                x0 = b[0]; y0 = b[1]; x2 = b[2]; y2 = b[3]; x1 = b[4]; y1 = b[5];
                oldx = x;
            } else {
                double xm = 0.5 * (x0 + x2);
                double ym = computeObjectiveValue(xm);
                if (FastMath.signum(y0) + FastMath.signum(ym) == 0.0) {
                    x2 = xm; y2 = ym;
                } else {
                    x0 = xm; y0 = ym;
                }
                x1 = 0.5 * (x0 + x2);
                y1 = computeObjectiveValue(x1);
                oldx = Double.POSITIVE_INFINITY;
            }
        }
    }
}