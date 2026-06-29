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


import org.apache.commons.math3.exception.NoBracketingException;
import org.apache.commons.math3.exception.TooManyEvaluationsException;
import org.apache.commons.math3.exception.NumberIsTooLargeException;
import org.apache.commons.math3.util.FastMath;
import org.apache.commons.math3.util.Precision;

/**
 * This class implements the <a href="http://mathworld.wolfram.com/BrentsMethod.html">
 * Brent algorithm</a> for finding zeros of real univariate functions.
 * The function should be continuous but not necessarily smooth.
 * The {@code solve} method returns a zero {@code x} of the function {@code f}
 * in the given interval {@code [a, b]} to within a tolerance
 * {@code 6 eps abs(x) + t} where {@code eps} is the relative accuracy and
 * {@code t} is the absolute accuracy.
 * The given interval must bracket the root.
 *
 * @version $Id$
 */
public class BrentSolver extends AbstractUnivariateSolver {

    /** Default absolute accuracy. */
    private static final double DEFAULT_ABSOLUTE_ACCURACY = 1e-6;

    /**
     * Construct a solver with default accuracy (1e-6).
     */
    public BrentSolver() {
        this(DEFAULT_ABSOLUTE_ACCURACY);
    }
    /**
     * Construct a solver.
     *
     * @param absoluteAccuracy Absolute accuracy.
     */
    public BrentSolver(double absoluteAccuracy) {
        super(absoluteAccuracy);
    }
    /**
     * Construct a solver.
     *
     * @param relativeAccuracy Relative accuracy.
     * @param absoluteAccuracy Absolute accuracy.
     */
    public BrentSolver(double relativeAccuracy,
                       double absoluteAccuracy) {
        super(relativeAccuracy, absoluteAccuracy);
    }
    /**
     * Construct a solver.
     *
     * @param relativeAccuracy Relative accuracy.
     * @param absoluteAccuracy Absolute accuracy.
     * @param functionValueAccuracy Function value accuracy.
     */
    public BrentSolver(double relativeAccuracy,
                       double absoluteAccuracy,
                       double functionValueAccuracy) {
        super(relativeAccuracy, absoluteAccuracy, functionValueAccuracy);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected double doSolve()
        throws NoBracketingException,
               TooManyEvaluationsException,
               NumberIsTooLargeException {
        double min = getMin();
        double max = getMax();
        final double initial = getStartValue();
        final double functionValueAccuracy = getFunctionValueAccuracy();

        verifySequence(min, initial, max);

        double yInitial = computeObjectiveValue(initial);
        if (FastMath.abs(yInitial) <= functionValueAccuracy) {
            return initial;
        }

        double yMin = computeObjectiveValue(min);
        if (FastMath.abs(yMin) <= functionValueAccuracy) {
            return min;
        }

        if (yInitial * yMin < 0) {
            return brent(min, initial, yMin, yInitial);
        }

        double yMax = computeObjectiveValue(max);
        if (FastMath.abs(yMax) <= functionValueAccuracy) {
            return max;
        }

        if (yInitial * yMax < 0) {
            return brent(initial, max, yInitial, yMax);
        }

        throw new NoBracketingException(min, max, yMin, yMax);
    }

    private interface InterpolationStrategy {
        void compute(double a, double b, double c, double fa, double fb, double fc, double m, double s, double[] result);
    }

    private static class LinearInterpolation implements InterpolationStrategy {
        public void compute(double a, double b, double c, double fa, double fb, double fc, double m, double s, double[] result) {
            result[0] = 2 * m * s;
            result[1] = 1 - s;
        }
    }

    private static class QuadraticInterpolation implements InterpolationStrategy {
        public void compute(double a, double b, double c, double fa, double fb, double fc, double m, double s, double[] result) {
            double q = fa / fc;
            double r = fb / fc;
            result[0] = s * (2 * m * q * (q - r) - (b - a) * (r - 1));
            result[1] = (q - 1) * (r - 1) * (s - 1);
        }
    }

    private double brent(double lo, double hi,
                         double fLo, double fHi) {
        double a = lo;
        double fa = fLo;
        double b = hi;
        double fb = fHi;
        double c = a;
        double fc = fa;
        double d = b - a;
        double e = d;

        final double t = getAbsoluteAccuracy();
        final double eps = getRelativeAccuracy();

        while (true) {
            if (FastMath.abs(fc) < FastMath.abs(fb)) {
                a = b;
                b = c;
                c = a;
                fa = fb;
                fb = fc;
                fc = fa;
            }

            final double tol = 2 * eps * FastMath.abs(b) + t;
            final double m = 0.5 * (c - b);

            if (FastMath.abs(m) <= tol ||
                Precision.equals(fb, 0))  {
                return b;
            }
            if (FastMath.abs(e) < tol ||
                FastMath.abs(fa) <= FastMath.abs(fb)) {
                d = m;
                e = d;
            } else {
                double s = fb / fa;
                double[] pq = new double[2];
                InterpolationStrategy strategy = (a == c) ? new LinearInterpolation() : new QuadraticInterpolation();
                strategy.compute(a, b, c, fa, fb, fc, m, s, pq);
                double p = pq[0];
                double q = pq[1];

                if (p > 0) {
                    q = -q;
                } else {
                    p = -p;
                }
                s = e;
                e = d;
                if (p >= 1.5 * m * q - FastMath.abs(tol * q) ||
                    p >= FastMath.abs(0.5 * s * q)) {
                    d = m;
                    e = d;
                } else {
                    d = p / q;
                }
            }
            a = b;
            fa = fb;

            if (FastMath.abs(d) > tol) {
                b += d;
            } else if (m > 0) {
                b += tol;
            } else {
                b -= tol;
            }
            fb = computeObjectiveValue(b);
            if ((fb > 0 && fc > 0) ||
                (fb <= 0 && fc <= 0)) {
                c = a;
                fc = fa;
                d = b - a;
                e = d;
            }
        }
    }
}