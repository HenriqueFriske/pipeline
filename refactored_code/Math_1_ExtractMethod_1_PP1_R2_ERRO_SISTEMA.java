// CHECKSTYLE: stop all
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
package org.apache.commons.math3.optim.nonlinear.scalar.noderiv;

import org.apache.commons.math3.exception.MathIllegalStateException;
import org.apache.commons.math3.exception.NumberIsTooSmallException;
import org.apache.commons.math3.exception.OutOfRangeException;
import org.apache.commons.math3.exception.util.LocalizedFormats;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.RealVector;
import org.apache.commons.math3.optim.nonlinear.scalar.GoalType;
import org.apache.commons.math3.optim.PointValuePair;
import org.apache.commons.math3.optim.nonlinear.scalar.MultivariateOptimizer;

/**
 * Powell's BOBYQA algorithm. This implementation is translated and
 * adapted from the Fortran version available
 * <a href="http://plato.asu.edu/ftp/other_software/bobyqa.zip">here</a>.
 * See <a href="http://www.optimization-online.org/DB_HTML/2010/05/2616.html">
 * this paper</a> for an introduction.
 * <br/>
 * BOBYQA is particularly well suited for high dimensional problems
 * where derivatives are not available. In most cases it outperforms the
 * {@link PowellOptimizer} significantly. Stochastic algorithms like
 * {@link CMAESOptimizer} succeed more often than BOBYQA, but are more
 * expensive. BOBYQA could also be considered as a replacement of any
 * derivative-based optimizer when the derivatives are approximated by
 * finite differences.
 *
 * @version $Id$
 * @since 3.0
 */
public class BOBYQAOptimizer
    extends MultivariateOptimizer {
    /** Minimum dimension of the problem: {@value} */
    public static final int MINIMUM_PROBLEM_DIMENSION = 2;
    /** Default value for {@link #initialTrustRegionRadius}: {@value} . */
    public static final double DEFAULT_INITIAL_RADIUS = 10.0;
    /** Default value for {@link #stoppingTrustRegionRadius}: {@value} . */
    public static final double DEFAULT_STOPPING_RADIUS = 1E-8;

    /**
     * Number of interpolation points.
     */
    private final int numberOfInterpolationPoints;
    /**
     * Initial trust region radius.
     */
    private double initialTrustRegionRadius;
    /**
     * Stopping trust region radius.
     */
    private final double stoppingTrustRegionRadius;
    /** Goal type (minimize or maximize). */
    private boolean isMinimize;
    /**
     * Current best values for the variables to be optimized.
     * The vector will be changed in-place to contain the values of the least
     * calculated objective function values.
     */
    private ArrayRealVector currentBest;
    /** Differences between the upper and lower bounds. */
    private double[] boundDifference;
    /**
     * Index of the interpolation point at the trust region center.
     */
    private int trustRegionCenterInterpolationPointIndex;
    /**
     * Last <em>n</em> columns of matrix H (where <em>n</em> is the dimension
     * of the problem).
     * Corresponds to "bmat" in the original code.
     */
    private Array2DRowRealMatrix bMatrix;
    /**
     * Factorization of the leading <em>npt</em> square submatrix of H, this
     * factorization being Z Z<sup>T</sup>, which provides both the correct
     * rank and positive semi-definiteness.
     * Corresponds to "zmat" in the original code.
     */
    private Array2DRowRealMatrix zMatrix;
    /**
     * Coordinates of the interpolation points relative to {@link #originShift}.
     * Corresponds to "xpt" in the original code.
     */
    private Array2DRowRealMatrix interpolationPoints;
    /**
     * Shift of origin that should reduce the contributions from rounding
     * errors to values of the model and Lagrange functions.
     * Corresponds to "xbase" in the original code.
     */
    private ArrayRealVector originShift;
    /**
     * Values of the objective function at the interpolation points.
     * Corresponds to "fval" in the original code.
     */
    private ArrayRealVector fAtInterpolationPoints;
    /**
     * Displacement from {@link #originShift} of the trust region center.
     * Corresponds to "xopt" in the original code.
     */
    private ArrayRealVector trustRegionCenterOffset;
    /**
     * Gradient of the quadratic model at {@link #originShift} +
     * {@link #trustRegionCenterOffset}.
     * Corresponds to "gopt" in the original code.
     */
    private ArrayRealVector gradientAtTrustRegionCenter;
    /**
     * Differences {@link #getLowerBound()} - {@link #originShift}.
     * All the components of every {@link #trustRegionCenterOffset} are going
     * to satisfy the bounds<br/>
     * {@link #getLowerBound() lowerBound}<sub>i</sub> &le;
     * {@link #trustRegionCenterOffset}<sub>i</sub>,<br/>
     * with appropriate equalities when {@link #trustRegionCenterOffset} is
     * on a constraint boundary.
     * Corresponds to "sl" in the original code.
     */
    private ArrayRealVector lowerDifference;
    /**
     * Differences {@link #getUpperBound()} - {@link #originShift}
     * All the components of every {@link #trustRegionCenterOffset} are going
     * to satisfy the bounds<br/>
     *  {@link #trustRegionCenterOffset}<sub>i</sub> &le;
     *  {@link #getUpperBound() upperBound}<sub>i</sub>,<br/>
     * with appropriate equalities when {@link #trustRegionCenterOffset} is
     * on a constraint boundary.
     * Corresponds to "su" in the original code.
     */
    private ArrayRealVector upperDifference;
    /**
     * Parameters of the implicit second derivatives of the quadratic model.
     * Corresponds to "pq" in the original code.
     */
    private ArrayRealVector modelSecondDerivativesParameters;
    /**
     * Point chosen by function {@link #trsbox(double,ArrayRealVector,
     * ArrayRealVector, ArrayRealVector,ArrayRealVector,ArrayRealVector) trsbox}
     * or {@link #altmov(int,double) altmov}.
     * Usually {@link #originShift} + {@link #newPoint} is the vector of
     * variables for the next evaluation of the objective function.
     * It also satisfies the constraints indicated in {@link #lowerDifference}
     * and {@link #upperDifference}.
     * Corresponds to "xnew" in the original code.
     */
    private ArrayRealVector newPoint;
    /**
     * Alternative to {@link #newPoint}, chosen by
     * {@link #altmov(int,double) altmov}.
     * It may replace {@link #newPoint} in order to increase the denominator
     * in the {@link #update(double, double, int) updating procedure}.
     * Corresponds to "xalt" in the original code.
     */
    private ArrayRealVector alternativeNewPoint;
    /**
     * Trial step from {@link #trustRegionCenterOffset} which is usually
     * {@link #newPoint} - {@link #trustRegionCenterOffset}.
     * Corresponds to "d__" in the original code.
     */
    private ArrayRealVector trialStepPoint;
    /**
     * Values of the Lagrange functions at a new point.
     * Corresponds to "vlag" in the original code.
     */
    private ArrayRealVector lagrangeValuesAtNewPoint;
    /**
     * Explicit second derivatives of the quadratic model.
     * Corresponds to "hq" in the original code.
     */
    private ArrayRealVector modelSecondDerivativesValues;

    /**
     * @param numberOfInterpolationPoints Number of interpolation conditions.
     * For a problem of dimension {@code n}, its value must be in the interval
     * {@code [n+2, (n+1)(n+2)/2]}.
     * Choices that exceed {@code 2n+1} are not recommended.
     */
    public BOBYQAOptimizer(int numberOfInterpolationPoints) {
        this(numberOfInterpolationPoints,
             DEFAULT_INITIAL_RADIUS,
             DEFAULT_STOPPING_RADIUS);
    }

    /**
     * @param numberOfInterpolationPoints Number of interpolation conditions.
     * For a problem of dimension {@code n}, its value must be in the interval
     * {@code [n+2, (n+1)(n+2)/2]}.
     * Choices that exceed {@code 2n+1} are not recommended.
     * @param initialTrustRegionRadius Initial trust region radius.
     * @param stoppingTrustRegionRadius Stopping trust region radius.
     */
    public BOBYQAOptimizer(int numberOfInterpolationPoints,
                           double initialTrustRegionRadius,
                           double stoppingTrustRegionRadius) {
        super(null); // No custom convergence criterion.
        this.numberOfInterpolationPoints = numberOfInterpolationPoints;
        this.initialTrustRegionRadius = initialTrustRegionRadius;
        this.stoppingTrustRegionRadius = stoppingTrustRegionRadius;
    }

    /** {@inheritDoc} */
    @Override
    protected PointValuePair doOptimize() {
        final double[] lowerBound = getLowerBound();
        final double[] upperBound = getUpperBound();

        // Validity checks.
        setup(lowerBound, upperBound);

        isMinimize = (getGoalType() == GoalType.MINIMIZE);
        currentBest = new ArrayRealVector(getStartPoint());

        final double value = bobyqa(lowerBound, upperBound);

        return new PointValuePair(currentBest.getDataRef(),
                                  isMinimize ? value : -value);
    }

    /**
     * This subroutine seeks the least value of a function of many variables,
     * by applying a trust region method that forms quadratic models by
     * interpolation. There is usually some freedom in the interpolation
     * conditions, which is taken up by minimizing the Frobenius norm of
     * the change to the second derivative of the model, beginning with the
     * zero matrix. The values of the variables are constrained by upper and
     * lower bounds. The arguments of the subroutine are as follows.
     *
     * N must be set to the number of variables and must be at least two.
     * NPT is the number of interpolation conditions. Its value must be in
     * the interval [N+2,(N+1)(N+2)/2]. Choices that exceed 2*N+1 are not
     * recommended.
     * Initial values of the variables must be set in X(1),X(2),...,X(N). They
     * will be changed to the values that give the least calculated F.
     * For I=1,2,...,N, XL(I) and XU(I) must provide the lower and upper
     * bounds, respectively, on X(I). The construction of quadratic models
     * requires XL(I) to be strictly less than XU(I) for each I. Further,
     * the contribution to a model from changes to the I-th variable is
     * damaged severely by rounding errors if XU(I)-XL(I) is too small.
     * RHOBEG and RHOEND must be set to the initial and final values of a trust
     * region radius, so both must be positive with RHOEND no greater than
     * RHOBEG. Typically, RHOBEG should be about one tenth of the greatest
     * expected change to a variable, while RHOEND should indicate the
     * accuracy that is required in the final values of the variables. An
     * error return occurs if any of the differences XU(I)-XL(I), I=1,...,N,
     * is less than 2*RHOBEG.
     * MAXFUN must be set to an upper bound on the number of calls of CALFUN.
     * The array W will be used for working space. Its length must be at least
     * (NPT+5)*(NPT+N)+3*N*(N+5)/2.
     *
     * @param lowerBound Lower bounds.
     * @param upperBound Upper bounds.
     * @return the value of the objective at the optimum.
     */
    private double bobyqa(double[] lowerBound,
                          double[] upperBound) {

        final int n = currentBest.getDimension();

        // Return if there is insufficient space between the bounds. Modify the
        // initial X if necessary in order to avoid conflicts between the bounds
        // and the construction of the first quadratic model. The lower and upper
        // bounds on moves from the updated X are set now, in the ISL and ISU
        // partitions of W, in order to provide useful and exact information about
        // components of X that become within distance RHOBEG from their bounds.

        for (int j = 0; j < n; j++) {
            final double boundDiff = boundDifference[j];
            lowerDifference.setEntry(j, lowerBound[j] - currentBest.getEntry(j));
            upperDifference.setEntry(j, upperBound[j] - currentBest.getEntry(j));

            if (lowerDifference.getEntry(j) >= -initialTrustRegionRadius) {
                if (lowerDifference.getEntry(j) >= 0.0) {
                    currentBest.setEntry(j, lowerBound[j]);
                    lowerDifference.setEntry(j, 0.0);
                    upperDifference.setEntry(j, boundDiff);
                } else {
                    currentBest.setEntry(j, lowerBound[j] + initialTrustRegionRadius);
                    lowerDifference.setEntry(j, -initialTrustRegionRadius);
                    final double deltaOne = upperBound[j] - currentBest.getEntry(j);
                    upperDifference.setEntry(j, Math.max(deltaOne, initialTrustRegionRadius));
                }
            } else if (upperDifference.getEntry(j) <= initialTrustRegionRadius) {
                if (upperDifference.getEntry(j) <= 0.0) {
                    currentBest.setEntry(j, upperBound[j]);
                    lowerDifference.setEntry(j, -boundDiff);
                    upperDifference.setEntry(j, 0.0);
                } else {
                    currentBest.setEntry(j, upperBound[j] - initialTrustRegionRadius);
                    final double deltaOne = lowerBound[j] - currentBest.getEntry(j);
                    final double deltaTwo = -initialTrustRegionRadius;
                    lowerDifference.setEntry(j, Math.min(deltaOne, deltaTwo));
                    upperDifference.setEntry(j, initialTrustRegionRadius);
                }
            }
        }

        // Make the call of BOBYQB.
        return bobyqb(lowerBound, upperBound);
    } // bobyqa

    /**
     * The arguments N, NPT, X, XL, XU, RHOBEG, RHOEND, IPRINT and MAXFUN
     * are identical to the corresponding arguments in SUBROUTINE BOBYQA.
     * XBASE holds a shift of origin that should reduce the contributions
     * from rounding errors to values of the model and Lagrange functions.
     * XPT is a two-dimensional array that holds the coordinates of the
     * interpolation points relative to XBASE.
     * FVAL holds the values of F at the interpolation points.
     * XOPT is set to the displacement from XBASE of the trust region centre.
     * GOPT holds the gradient of the quadratic model at XBASE+XOPT.
     * HQ holds the explicit second derivatives of the quadratic model.
     * PQ contains the parameters of the implicit second derivatives of the
     * quadratic model.
     * BMAT holds the last N columns of H.
     * ZMAT holds the factorization of the leading NPT by NPT submatrix of H,
     * this factorization being ZMAT times ZMAT^T, which provides both the
     * correct rank and positive semi-definiteness.
     * NDIM is the first dimension of BMAT and has the value NPT+N.
     * SL and SU hold the differences XL-XBASE and XU-XBASE, respectively.
     * All the components of every XOPT are going to satisfy the bounds
     * SL(I) .LEQ. XOPT(I) .LEQ. SU(I), with appropriate equalities when
     * XOPT is on a constraint boundary.
     * XNEW is chosen by SUBROUTINE TRSBOX or ALTMOV. Usually XBASE+XNEW is the
     * vector of variables for the next call of CALFUN. XNEW also satisfies
     * the SL and SU constraints in the way that has just been mentioned.
     * XALT is an alternative to XNEW, chosen by ALTMOV, that may replace XNEW
     * in order to increase the denominator in the updating of UPDATE.
     * D is reserved for a trial step from XOPT, which is usually XNEW-XOPT.
     * VLAG contains the values of the Lagrange functions at a new point X.
     * They are part of a product that requires VLAG to be of length NDIM.
     * W is a one-dimensional array that is used for working space. Its length
     * must be at least 3*NDIM = 3*(NPT+N).
     *
     * @param lowerBound Lower bounds.
     * @param upperBound Upper bounds.
     * @return the value of the objective at the optimum.
     */
    private double bobyqb(double[] lowerBound,
                          double[] upperBound) {

        final int n = currentBest.getDimension();
        final int npt = numberOfInterpolationPoints;
        final int np = n + 1;
        final int nptm = npt - np;
        final int nh = n * np / 2;

        final ArrayRealVector work1 = new ArrayRealVector(n);
        final ArrayRealVector work2 = new ArrayRealVector(npt);
        final ArrayRealVector work3 = new ArrayRealVector(npt);

        double cauchy = Double.NaN;
        double alpha = Double.NaN;
        double dsq = Double.NaN;
        double crvmin = Double.NaN;

        trustRegionCenterInterpolationPointIndex = 0;

        prelim(lowerBound, upperBound);

        double xoptsq = 0.0;
        for (int i = 0; i < n; i++) {
            trustRegionCenterOffset.setEntry(i, interpolationPoints.getEntry(trustRegionCenterInterpolationPointIndex, i));
            xoptsq += trustRegionCenterOffset.getEntry(i) * trustRegionCenterOffset.getEntry(i);
        }
        double fsave = fAtInterpolationPoints.getEntry(0);
        final int kbase = 0;

        int ntrits = 0;
        int itest = 0;
        int knew = 0;
        int nfsav = getEvaluations();
        double rho = initialTrustRegionRadius;
        double delta = rho;
        double diffa = 0.0;
        double diffb = 0.0;
        double diffc = 0.0;
        double f = 0.0;
        double beta = 0.0;
        double adelt = 0.0;
        double denom = 0.0;
        double ratio = 0.0;
        double dnorm = 0.0;
        double scaden = 0.0;
        double biglsq = 0.0;
        double distsq = 0.0;

        int state = 20;
        for(;;) switch (state) {
        case 20: {
            // Update GOPT if necessary before the first iteration and after each
            // call of RESCUE that makes a call of CALFUN.
            if (trustRegionCenterInterpolationPointIndex != kbase) {
                int ih = 0;
                for (int j = 0; j < n; j++) {
                    for (int i = 0; i <= j; i++) {
                        if (i < j) {
                            gradientAtTrustRegionCenter.setEntry(j, gradientAtTrustRegionCenter.getEntry(j) + modelSecondDerivativesValues.getEntry(ih) * trustRegionCenterOffset.getEntry(i));
                        }
                        gradientAtTrustRegionCenter.setEntry(i, gradientAtTrustRegionCenter.getEntry(i) + modelSecondDerivativesValues.getEntry(ih) * trustRegionCenterOffset.getEntry(j));
                        ih++;
                    }
                }
                if (getEvaluations() > npt) {
                    for (int k = 0; k < npt; k++) {
                        double temp = 0.0;
                        for (int j = 0; j < n; j++) {
                            temp += interpolationPoints.getEntry(k, j) * trustRegionCenterOffset.getEntry(j);
                        }
                        temp *= modelSecondDerivativesParameters.getEntry(k);
                        for (int i = 0; i < n; i++) {
                            gradientAtTrustRegionCenter.setEntry(i, gradientAtTrustRegionCenter.getEntry(i) + temp * interpolationPoints.getEntry(k, i));
                        }
                    }
                }
            }
            // Generate the next point in the trust region that provides a small value
            // of the quadratic model subject to the constraints on the variables.
            // The int NTRITS is set to the number "trust region" iterations that
            // have occurred since the last "alternative" iteration. If the length
            // of XNEW-XOPT is less than HALF*RHO, however, then there is a branch to
            // label 650 or 680 with NTRITS=-1, instead of calculating F at XNEW.
        }
        case 60: {
            final ArrayRealVector gnew = new ArrayRealVector(n);
            final ArrayRealVector xbdi = new ArrayRealVector(n);
            final ArrayRealVector s = new ArrayRealVector(n);
            final ArrayRealVector hs = new ArrayRealVector(n);
            final ArrayRealVector hred = new ArrayRealVector(n);

            final double[] dsqCrvmin = trsbox(delta, gnew, xbdi, s,
                                              hs, hred);
            dsq = dsqCrvmin[0];
            crvmin = dsqCrvmin[1];

            dnorm = Math.min(delta, Math.sqrt(dsq));

            if (dnorm < 0.5 * rho) {
                ntrits = -1;
                distsq = (10.0 * rho) * (10.0 * rho);
                if (getEvaluations() <= nfsav + 2) {
                    state = 650; break;
                }

                // The following choice between labels 650 and 680 depends on whether or
                // not our work with the current RHO seems to be complete. Either RHO is
                // decreased or termination occurs if the errors in the quadratic model at
                // the last three interpolation points compare favourably with predictions
                // of likely improvements to the model within distance HALF*RHO of XOPT.

                final double errbig = Math.max(diffa, Math.max(diffb, diffc));
                final double frhosq = rho * 0.125 * rho;
                if (crvmin > 0.0 && errbig > frhosq * crvmin) {
                    state = 650; break;
                }
                final double bdtol = errbig / rho;
                for (int j = 0; j < n; j++) {
                    double bdtest = bdtol;
                    if (newPoint.getEntry(j) == lowerDifference.getEntry(j)) {
                        bdtest = work1.getEntry(j);
                    }
                    if (newPoint.getEntry(j) == upperDifference.getEntry(j)) {
                        bdtest = -work1.getEntry(j);
                    }
                    if (bdtest < bdtol) {
                        double curv = modelSecondDerivativesValues.getEntry((j + j * j) / 2);
                        for (int k = 0; k < npt; k++) {
                            final double d1 = interpolationPoints.getEntry(k, j);
                            curv += modelSecondDerivativesParameters.getEntry(k) * (d1 * d1);
                        }
                        bdtest += 0.5 * curv * rho;
                        if (bdtest < bdtol) {
                            state = 650; break;
                        }
                    }
                }
                state = 680; break;
            }
            ++ntrits;

            // Severe cancellation is likely to occur if XOPT is too far from XBASE.
            // If the following test holds, then XBASE is shifted so that XOPT becomes
            // zero. The appropriate changes are made to BMAT and to the second
            // derivatives of the current model, beginning with the changes to BMAT
            // that do not depend on ZMAT. VLAG is used temporarily for working space.

        }
        case 90: {
            if (dsq <= xoptsq * 0.001) {
                final double fracsq = xoptsq * 0.25;
                double sumpq = 0.0;
                for (int k = 0; k < npt; k++) {
                    sumpq += modelSecondDerivativesParameters.getEntry(k);
                    double sum = -0.5 * xoptsq;
                    for (int i = 0; i < n; i++) {
                        sum += interpolationPoints.getEntry(k, i) * trustRegionCenterOffset.getEntry(i);
                    }
                    work2.setEntry(k, sum);
                    final double tempVal = fracsq - 0.5 * sum;
                    for (int i = 0; i < n; i++) {
                        work1.setEntry(i, bMatrix.getEntry(k, i));
                        lagrangeValuesAtNewPoint.setEntry(i, sum * interpolationPoints.getEntry(k, i) + tempVal * trustRegionCenterOffset.getEntry(i));
                        final int ip = npt + i;
                        for (int j = 0; j <= i; j++) {
                            bMatrix.setEntry(ip, j,
                                          bMatrix.getEntry(ip, j)
                                          + work1.getEntry(i) * lagrangeValuesAtNewPoint.getEntry(j)
                                          + lagrangeValuesAtNewPoint.getEntry(i) * work1.getEntry(j));
                        }
                    }
                }

                // Then the revisions of BMAT that depend on ZMAT are calculated.

                for (int m = 0; m < nptm; m++) {
                    double sumz = 0.0;
                    double sumw = 0.0;
                    for (int k = 0; k < npt; k++) {
                        sumz += zMatrix.getEntry(k, m);
                        lagrangeValuesAtNewPoint.setEntry(k, work2.getEntry(k) * zMatrix.getEntry(k, m));
                        sumw += lagrangeValuesAtNewPoint.getEntry(k);
                    }
                    for (int j = 0; j < n; j++) {
                        double sum = (fracsq * sumz - 0.5 * sumw) * trustRegionCenterOffset.getEntry(j);
                        for (int k = 0; k < npt; k++) {
                            sum += lagrangeValuesAtNewPoint.getEntry(k) * interpolationPoints.getEntry(k, j);
                        }
                        work1.setEntry(j, sum);
                        for (int k = 0; k < npt; k++) {
                            bMatrix.setEntry(k, j,
                                          bMatrix.getEntry(k, j)
                                          + sum * zMatrix.getEntry(k, m));
                        }
                    }
                    for (int i = 0; i < n; i++) {
                        final int ip = i + npt;
                        final double tempVal = work1.getEntry(i);
                        for (int j = 0; j <= i; j++) {
                            bMatrix.setEntry(ip, j,
                                          bMatrix.getEntry(ip, j)
                                          + tempVal * work1.getEntry(j));
                        }
                    }
                }

                // The following instructions complete the shift, including the changes
                // to the second derivative parameters of the quadratic model.

                int ih = 0;
                for (int j = 0; j < n; j++) {
                    work1.setEntry(j, -0.5 * sumpq * trustRegionCenterOffset.getEntry(j));
                    for (int k = 0; k < npt; k++) {
                        work1.setEntry(j, work1.getEntry(j) + modelSecondDerivativesParameters.getEntry(k) * interpolationPoints.getEntry(k, j));
                        interpolationPoints.setEntry(k, j, interpolationPoints.getEntry(k, j) - trustRegionCenterOffset.getEntry(j));
                    }
                    for (int i = 0; i <= j; i++) {
                         modelSecondDerivativesValues.setEntry(ih,
                                    modelSecondDerivativesValues.getEntry(ih)
                                    + work1.getEntry(i) * trustRegionCenterOffset.getEntry(j)
                                    + trustRegionCenterOffset.getEntry(i) * work1.getEntry(j));
                        bMatrix.setEntry(npt + i, j, bMatrix.getEntry(npt + j, i));
                        ih++;
                    }
                }
                for (int i = 0; i < n; i++) {
                    originShift.setEntry(i, originShift.getEntry(i) + trustRegionCenterOffset.getEntry(i));
                    newPoint.setEntry(i, newPoint.getEntry(i) - trustRegionCenterOffset.getEntry(i));
                    lowerDifference.setEntry(i, lowerDifference.getEntry(i) - trustRegionCenterOffset.getEntry(i));
                    upperDifference.setEntry(i, upperDifference.getEntry(i) - trustRegionCenterOffset.getEntry(i));
                    trustRegionCenterOffset.setEntry(i, 0.0);
                }
                xoptsq = 0.0;
            }
            if (ntrits == 0) {
                state = 210; break;
            }
            state = 230; break;
        }
        case 210: {
            // Pick two alternative vectors of variables, relative to XBASE, that
            // are suitable as new positions of the KNEW-th interpolation point.
            // Firstly, XNEW is set to the point on a line through XOPT and another
            // interpolation point that minimizes the predicted value of the next
            // denominator, subject to ||XNEW - XOPT|| .LEQ. ADELT and to the SL
            // and SU bounds. Secondly, XALT is set to the best feasible point on
            // a constrained version of the Cauchy step of the KNEW-th Lagrange
            // function, the corresponding value of the square of this function
            // being returned in CAUCHY. The choice between these alternatives is
            // going to be made when the denominator is calculated.

            final double[] alphaCauchy = altmov(knew, adelt);
            alpha = alphaCauchy[0];
            cauchy = alphaCauchy[1];

            for (int i = 0; i < n; i++) {
                trialStepPoint.setEntry(i, newPoint.getEntry(i) - trustRegionCenterOffset.getEntry(i));
            }

            // Calculate VLAG and BETA for the current choice of D. The scalar
            // product of D with XPT(K,.) is going to be held in W(NPT+K) for
            // use when VQUAD is calculated.

        }
        case 230: {
            for (int k = 0; k < npt; k++) {
                double suma = 0.0;
                double sumb = 0.0;
                double sum = 0.0;
                for (int j = 0; j < n; j++) {
                    suma += interpolationPoints.getEntry(k, j) * trialStepPoint.getEntry(j);
                    sumb += interpolationPoints.getEntry(k, j) * trustRegionCenterOffset.getEntry(j);
                    sum += bMatrix.getEntry(k, j) * trialStepPoint.getEntry(j);
                }
                work3.setEntry(k, suma * (0.5 * suma + sumb));
                lagrangeValuesAtNewPoint.setEntry(k, sum);
                work2.setEntry(k, suma);
            }
            beta = 0.0;
            for (int m = 0; m < nptm; m++) {
                double sum = 0.0;
                for (int k = 0; k < npt; k++) {
                    sum += zMatrix.getEntry(k, m) * work3.getEntry(k);
                }
                beta -= sum * sum;
                for (int k = 0; k < npt; k++) {
                    lagrangeValuesAtNewPoint.setEntry(k, lagrangeValuesAtNewPoint.getEntry(k) + sum * zMatrix.getEntry(k, m));
                }
            }
            dsq = 0.0;
            double bsum = 0.0;
            double dx = 0.0;
            for (int j = 0; j < n; j++) {
                dsq += trialStepPoint.getEntry(j) * trialStepPoint.getEntry(j);
                double sum = 0.0;
                for (int k = 0; k < npt; k++) {
                    sum += work3.getEntry(k) * bMatrix.getEntry(k, j);
                }
                bsum += sum * trialStepPoint.getEntry(j);
                final int jp = npt + j;
                for (int i = 0; i < n; i++) {
                    sum += bMatrix.getEntry(jp, i) * trialStepPoint.getEntry(i);
                }
                lagrangeValuesAtNewPoint.setEntry(jp, sum);
                bsum += sum * trialStepPoint.getEntry(j);
                dx += trialStepPoint.getEntry(j) * trustRegionCenterOffset.getEntry(j);
            }

            beta = dx * dx + dsq * (xoptsq + dx + dx + 0.5 * dsq) + beta - bsum;

            lagrangeValuesAtNewPoint.setEntry(trustRegionCenterInterpolationPointIndex,
                          lagrangeValuesAtNewPoint.getEntry(trustRegionCenterInterpolationPointIndex) + 1.0);

            // If NTRITS is zero, the denominator may be increased by replacing
            // the step D of ALTMOV by a Cauchy step. Then RESCUE may be called if
            // rounding errors have damaged the chosen denominator.

            if (ntrits == 0) {
                denom = lagrangeValuesAtNewPoint.getEntry(knew) * lagrangeValuesAtNewPoint.getEntry(knew) + alpha * beta;
                if (denom < cauchy && cauchy > 0.0) {
                    for (int i = 0; i < n; i++) {
                        newPoint.setEntry(i, alternativeNewPoint.getEntry(i));
                        trialStepPoint.setEntry(i, newPoint.getEntry(i) - trustRegionCenterOffset.getEntry(i));
                    }
                    cauchy = 0.0; // Useful statement?
                    state = 230; break;
                }
                // Alternatively, if NTRITS is positive, then set KNEW to the index of
                // the next interpolation point to be deleted to make room for a trust
                // region step. Again RESCUE may be called if rounding errors have damaged_
            // the chosen denominator, which is the reason for attempting to select
                // KNEW before calculating the next value of the objective function.

            } else {
                final double delsq = delta * delta;
                scaden = 0.0;
                biglsq = 0.0;
                knew = 0;
                for (int k = 0; k < npt; k++) {
                    if (k == trustRegionCenterInterpolationPointIndex) {
                        continue;
                    }
                    double hdiag = 0.0;
                    for (int m = 0; m < nptm; m++) {
                        hdiag += zMatrix.getEntry(k, m) * zMatrix.getEntry(k, m);
                    }
                    final double vlagK = lagrangeValuesAtNewPoint.getEntry(k);
                    final double den = beta * hdiag + vlagK * vlagK;
                    distsq = 0.0;
                    for (int j = 0; j < n; j++) {
                        final double diffCoord = interpolationPoints.getEntry(k, j) - trustRegionCenterOffset.getEntry(j);
                        distsq += diffCoord * diffCoord;
                    }
                    final double tempRatio = distsq / delsq;
                    final double tempFactor = Math.max(1.0, tempRatio * tempRatio);
                    if (tempFactor * den > scaden) {
                        scaden = tempFactor * den;
                        knew = k;
                        denom = den;
                    }
                    biglsq = Math.max(biglsq, tempFactor * (vlagK * vlagK));
                }
            }

            // Put the variables for the next calculation of the objective function
            //   in XNEW, with any adjustments for the bounds.

            // Calculate the value of the objective function at XBASE+XNEW, unless
            //   the limit on the number of calculations of F has been reached.

        }
        case 360: {
            for (int i = 0; i < n; i++) {
                final double lowerVal = lowerBound[i];
                final double upperVal = upperBound[i];
                final double shiftedNewPoint = originShift.getEntry(i) + newPoint.getEntry(i);
                currentBest.setEntry(i, Math.min(Math.max(lowerVal, shiftedNewPoint),
                                                 upperVal));
                if (newPoint.getEntry(i) == lowerDifference.getEntry(i)) {
                    currentBest.setEntry(i, lowerBound[i]);
                }
                if (newPoint.getEntry(i) == upperDifference.getEntry(i)) {
                    currentBest.setEntry(i, upperBound[i]);
                }
            }

            f = computeObjectiveValue(currentBest.toArray());

            if (!isMinimize)
                f = -f;
            if (ntrits == -1) {
                fsave = f;
                state = 720; break;
            }

            // Use the quadratic model to predict the change in F due to the step D,
            //   and set DIFF to the error of this prediction.

            final double fopt = fAtInterpolationPoints.getEntry(trustRegionCenterInterpolationPointIndex);
            double vquad = 0.0;
            int ih = 0;
            for (int j = 0; j < n; j++) {
                vquad += trialStepPoint.getEntry(j) * gradientAtTrustRegionCenter.getEntry(j);
                for (int i = 0; i <= j; i++) {
                    double tempTerm = trialStepPoint.getEntry(i) * trialStepPoint.getEntry(j);
                    if (i == j) {
                        tempTerm *= 0.5;
                    }
                    vquad += modelSecondDerivativesValues.getEntry(ih) * tempTerm;
                    ih++;
               }
            }
            for (int k = 0; k < npt; k++) {
                final double work2EntryK = work2.getEntry(k);
                vquad += 0.5 * modelSecondDerivativesParameters.getEntry(k) * (work2EntryK * work2EntryK);
            }
            final double diff = f - fopt - vquad;
            diffc = diffb;
            diffb = diffa;
            diffa = Math.abs(diff);
            if (dnorm > rho) {
                nfsav = getEvaluations();
            }

            // Pick the next value of DELTA after a trust region step.

            if (ntrits > 0) {
                if (vquad >= 0.0) {
                    throw new MathIllegalStateException(LocalizedFormats.TRUST_REGION_STEP_FAILED, vquad);
                }
                ratio = (f - fopt) / vquad;
                final double hDelta = 0.5 * delta;
                if (ratio <= 0.1) {
                    delta = Math.min(hDelta, dnorm);
                } else if (ratio <= 0.7) {
                    delta = Math.max(hDelta, dnorm);
                } else {
                    delta = Math.max(hDelta, 2.0 * dnorm);
                }
                if (delta <= rho * 1.5) {
                    delta = rho;
                }

                // Recalculate KNEW and DENOM if the new F is less than FOPT.

                if (f < fopt) {
                    final int ksav = knew;
                    final double densav = denom;
                    final double delsq = delta * delta;
                    scaden = 0.0;
                    biglsq = 0.0;
                    knew = 0;
                    for (int k = 0; k < npt; k++) {
                        double hdiag = 0.0;
                        for (int m = 0; m < nptm; m++) {
                            hdiag += zMatrix.getEntry(k, m) * zMatrix.getEntry(k, m);
                        }
                        final double vlagK = lagrangeValuesAtNewPoint.getEntry(k);
                        final double den = beta * hdiag + vlagK * vlagK;
                        distsq = 0.0;
                        for (int j = 0; j < n; j++) {
                            final double diffCoord = interpolationPoints.getEntry(k, j) - newPoint.getEntry(j);
                            distsq += diffCoord * diffCoord;
                        }
                        final double tempRatio = distsq / delsq;
                        final double tempFactor = Math.max(1.0, tempRatio * tempRatio);
                        if (tempFactor * den > scaden) {
                            scaden = tempFactor * den;
                            knew = k;
                            denom = den;
                        }
                        biglsq = Math.max(biglsq, tempFactor * (vlagK * vlagK));
                    }
                    if (scaden <= 0.5 * biglsq) {
                        knew = ksav;
                        denom = densav;
                    }
                }
            }

            // Update BMAT and ZMAT, so that the KNEW-th interpolation point can be
            // moved. Also update the second derivative terms of the model.

            update(beta, denom, knew);

            ih = 0;
            final double pqold = modelSecondDerivativesParameters.getEntry(knew);
            modelSecondDerivativesParameters.setEntry(knew, 0.0);
            for (int i = 0; i < n; i++) {
                final double temp = pqold * interpolationPoints.getEntry(knew, i);
                for (int j = 0; j <= i; j++) {
                    modelSecondDerivativesValues.setEntry(ih, modelSecondDerivativesValues.getEntry(ih) + temp * interpolationPoints.getEntry(knew, j));
                    ih++;
                }
            }
            for (int m = 0; m < nptm; m++) {
                final double temp = diff * zMatrix.getEntry(knew, m);
                for (int k = 0; k < npt; k++) {
                    modelSecondDerivativesParameters.setEntry(k, modelSecondDerivativesParameters.getEntry(k) + temp * zMatrix.getEntry(k, m));
                }
            }

            // Include the new interpolation point, and make the changes to GOPT at
            // the old XOPT that are caused by the updating of the quadratic model.

            fAtInterpolationPoints.setEntry(knew,  f);
            for (int i = 0; i < n; i++) {
                interpolationPoints.setEntry(knew, i, newPoint.getEntry(i));
                work1.setEntry(i, bMatrix.getEntry(knew, i));
            }
            for (int k = 0; k < npt; k++) {
                double suma = 0.0;
                for (int m = 0; m < nptm; m++) {
                    suma += zMatrix.getEntry(knew, m) * zMatrix.getEntry(k, m);
                }
                double sumb = 0.0;
                for (int j = 0; j < n; j++) {
                    sumb += interpolationPoints.getEntry(k, j) * trustRegionCenterOffset.getEntry(j);
                }
                final double temp = suma * sumb;
                for (int i = 0; i < n; i++) {
                    work1.setEntry(i, work1.getEntry(i) + temp * interpolationPoints.getEntry(k, i));
                }
            }
            for (int i = 0; i < n; i++) {
                gradientAtTrustRegionCenter.setEntry(i, gradientAtTrustRegionCenter.getEntry(i) + diff * work1.getEntry(i));
            }

            // Update XOPT, GOPT and KOPT if the new calculated F is less than FOPT.

            if (f < fopt) {
                trustRegionCenterInterpolationPointIndex = knew;
                xoptsq = 0.0;
                ih = 0;
                for (int j = 0; j < n; j++) {
                    trustRegionCenterOffset.setEntry(j, newPoint.getEntry(j));
                    xoptsq += trustRegionCenterOffset.getEntry(j) * trustRegionCenterOffset.getEntry(j);
                    for (int i = 0; i <= j; i++) {
                        if (i < j) {
                            gradientAtTrustRegionCenter.setEntry(j, gradientAtTrustRegionCenter.getEntry(j) + modelSecondDerivativesValues.getEntry(ih) * trialStepPoint.getEntry(i));
                        }
                        gradientAtTrustRegionCenter.setEntry(i, gradientAtTrustRegionCenter.getEntry(i) + modelSecondDerivativesValues.getEntry(ih) * trialStepPoint.getEntry(j));
                        ih++;
                    }
                }
                for (int k = 0; k < npt; k++) {
                    double temp = 0.0;
                    for (int j = 0; j < n; j++) {
                        temp += interpolationPoints.getEntry(k, j) * trialStepPoint.getEntry(j);
                    }
                    temp *= modelSecondDerivativesParameters.getEntry(k);
                    for (int i = 0; i < n; i++) {
                        gradientAtTrustRegionCenter.setEntry(i, gradientAtTrustRegionCenter.getEntry(i) + temp * interpolationPoints.getEntry(k, i));
                    }
                }
            }

            // Calculate the parameters of the least Frobenius norm interpolant to
            // the current data, the gradient of this interpolant at XOPT being put
            // into VLAG(NPT+I), I=1,2,...,N.

            if (ntrits > 0) {
                for (int k = 0; k < npt; k++) {
                    lagrangeValuesAtNewPoint.setEntry(k, fAtInterpolationPoints.getEntry(k) - fAtInterpolationPoints.getEntry(trustRegionCenterInterpolationPointIndex));
                    work3.setEntry(k, 0.0);
                }
                for (int j = 0; j < nptm; j++) {
                    double sum = 0.0;
                    for (int k = 0; k < npt; k++) {
                        sum += zMatrix.getEntry(k, j) * lagrangeValuesAtNewPoint.getEntry(k);
                    }
                    for (int k = 0; k < npt; k++) {
                        work3.setEntry(k, work3.getEntry(k) + sum * zMatrix.getEntry(k, j));
                    }
                }
                for (int k = 0; k < npt; k++) {
                    double sum = 0.0;
                    for (int j = 0; j < n; j++) {
                        sum += interpolationPoints.getEntry(k, j) * trustRegionCenterOffset.getEntry(j);
                    }
                    work2.setEntry(k, work3.getEntry(k));
                    work3.setEntry(k, sum * work3.getEntry(k));
                }
                double gqsq = 0.0;
                double gisq = 0.0;
                for (int i = 0; i < n; i++) {
                    double sum = 0.0;
                    for (int k = 0; k < npt; k++) {
                        sum += bMatrix.getEntry(k, i) *
                            lagrangeValuesAtNewPoint.getEntry(k) + interpolationPoints.getEntry(k, i) * work3.getEntry(k);
                    }
                    if (trustRegionCenterOffset.getEntry(i) == lowerDifference.getEntry(i)) {
                        gqsq += Math.min(0.0, gradientAtTrustRegionCenter.getEntry(i)) * Math.min(0.0, gradientAtTrustRegionCenter.getEntry(i));
                        gisq += Math.min(0.0, sum) * Math.min(0.0, sum);
                    } else if (trustRegionCenterOffset.getEntry(i) == upperDifference.getEntry(i)) {
                        gqsq += Math.max(0.0, gradientAtTrustRegionCenter.getEntry(i)) * Math.max(0.0, gradientAtTrustRegionCenter.getEntry(i));
                        gisq += Math.max(0.0, sum) * Math.max(0.0, sum);
                    } else {
                        gqsq += gradientAtTrustRegionCenter.getEntry(i) * gradientAtTrustRegionCenter.getEntry(i);
                        gisq += sum * sum;
                    }
                    lagrangeValuesAtNewPoint.setEntry(npt + i, sum);
                }

                // Test whether to replace the new quadratic model by the least Frobenius
                // norm interpolant, making the replacement if the test is satisfied.

                ++itest;
                if (gqsq < 10.0 * gisq) {
                    itest = 0;
                }
                if (itest >= 3) {
                    for (int i = 0, max = Math.max(npt, nh); i < max; i++) {
                        if (i < n) {
                            gradientAtTrustRegionCenter.setEntry(i, lagrangeValuesAtNewPoint.getEntry(npt + i));
                        }
                        if (i < npt) {
                            modelSecondDerivativesParameters.setEntry(i, work2.getEntry(i));
                        }
                        if (i < nh) {
                            modelSecondDerivativesValues.setEntry(i, 0.0);
                        }
                        itest = 0;
                    }
                }
            }

            // If a trust region step has provided a sufficient decrease in F, then
            // branch for another trust region calculation. The case NTRITS=0 occurs
            // when the new interpolation point was reached by an alternative step.

            if (ntrits == 0) {
                state = 60; break;
            }
            if (f <= fopt + 0.1 * vquad) {
                state = 60; break;
            }

            // Alternatively, find out if the interpolation points are close enough
            //   to the best point so far.

            distsq = Math.max((2.0 * delta) * (2.0 * delta), (10.0 * rho) * (10.0 * rho));
        }
        case 650: {
            knew = -1;
            for (int k = 0; k < npt; k++) {
                double sum = 0.0;
                for (int j = 0; j < n; j++) {
                    final double diffCoord = interpolationPoints.getEntry(k, j) - trustRegionCenterOffset.getEntry(j);
                    sum += diffCoord * diffCoord;
                }
                if (sum > distsq) {
                    knew = k;
                    distsq = sum;
                }
            }

            // If KNEW is positive, then ALTMOV finds alternative new positions for
            // the KNEW-th interpolation point within distance ADELT of XOPT. It is
            // reached via label 90. Otherwise, there is a branch to label 60 for
            // another trust region iteration, unless the calculations with the
            // current RHO are complete.

            if (knew >= 0) {
                final double dist = Math.sqrt(distsq);
                if (ntrits == -1) {
                    delta = Math.min(0.1 * delta, 0.5 * dist);
                    if (delta <= rho * 1.5) {
                        delta = rho;
                    }
                }
                ntrits = 0;
                adelt = Math.max(Math.min(0.1 * dist, delta), rho);
                dsq = adelt * adelt;
                state = 90; break;
            }
            if (ntrits == -1) {
                state = 680; break;
            }
            if (ratio > 0.0) {
                state = 60; break;
            }
            if (Math.max(delta, dnorm) > rho) {
                state = 60; break;
            }

            // The calculations with the current value of RHO are complete. Pick the
            //   next values of RHO and DELTA.
        }
        case 680: {
            if (rho > stoppingTrustRegionRadius) {
                delta = 0.5 * rho;
                ratio = rho / stoppingTrustRegionRadius;
                if (ratio <= 16.0) {
                    rho = stoppingTrustRegionRadius;
                } else if (ratio <= 250.0) {
                    rho = Math.sqrt(ratio) * stoppingTrustRegionRadius;
                } else {
                    rho *= 0.1;
                }
                delta = Math.max(delta, rho);
                ntrits = 0;
                nfsav = getEvaluations();
                state = 60; break;
            }

            // Return from the calculation, after another Newton-Raphson step, if
            //   it is too short to have been tried before.

            if (ntrits == -1) {
                state = 360; break;
            }
        }
        case 720: {
            if (fAtInterpolationPoints.getEntry(trustRegionCenterInterpolationPointIndex) <= fsave) {
                for (int i = 0; i < n; i++) {
                    final double lowerVal = lowerBound[i];
                    final double upperVal = upperBound[i];
                    final double shiftedTrustRegionCenter = originShift.getEntry(i) + trustRegionCenterOffset.getEntry(i);
                    currentBest.setEntry(i, Math.min(Math.max(lowerVal, shiftedTrustRegionCenter),
                                                     upperVal));
                    if (trustRegionCenterOffset.getEntry(i) == lowerDifference.getEntry(i)) {
                        currentBest.setEntry(i, lowerBound[i]);
                    }
                    if (trustRegionCenterOffset.getEntry(i) == upperDifference.getEntry(i)) {
                        currentBest.setEntry(i, upperBound[i]);
                    }
                }
                f = fAtInterpolationPoints.getEntry(trustRegionCenterInterpolationPointIndex);
            }
            return f;
        }
        default: {
            throw new MathIllegalStateException(LocalizedFormats.SIMPLE_MESSAGE, "bobyqb");
        }}
    } // bobyqb

    /**
     * The arguments N, NPT, XPT, XOPT, BMAT, ZMAT, NDIM, SL and SU all have
     * the same meanings as the corresponding arguments of BOBYQB.
     * KOPT is the index of the optimal interpolation point.
     * KNEW is the index of the interpolation point that is going to be moved.
     * ADELT is the current trust region bound.
     * XNEW will be set to a suitable new position for the interpolation point
     * XPT(KNEW,.). Specifically, it satisfies the SL, SU and trust region
     * bounds and it should provide a large denominator in the next call of
     * UPDATE. The step XNEW-XOPT from XOPT is restricted to moves along the
     * straight lines through XOPT and another interpolation point.
     * XALT also provides a large value of the modulus of the KNEW-th Lagrange
     * function subject to the constraints that have been mentioned, its main
     * difference from XNEW being that XALT-XOPT is a constrained version of
     * the Cauchy step within the trust region. An exception is that XALT is
     * not calculated if all components of GLAG (see below) are zero.
     * ALPHA will be set to the KNEW-th diagonal element of the H matrix.
     * CAUCHY will be set to the square of the KNEW-th Lagrange function at
     * the step XALT-XOPT from XOPT for the vector XALT that is returned,
     * except that CAUCHY is set to zero if XALT is not calculated.
     * GLAG is a working space vector of length N for the gradient of the
     * KNEW-th Lagrange function at XOPT.
     * HCOL is a working space vector of length NPT for the second derivative
     * coefficients of the KNEW-th Lagrange function.
     * W is a working space vector of length 2N that is going to hold the
     * constrained Cauchy step from XOPT of the Lagrange function, followed
     * by the downhill version of XALT when the uphill step is calculated.
     *
     * Set the first NPT components of W to the leading elements of the
     * KNEW-th column of the H matrix.
     * @param knew Index of the interpolation point to be moved.
     * @param adelt Current trust region bound.
     * @return an array containing alpha and cauchy values.
     */
    private double[] altmov(
            int knew,
            double adelt
    ) {

        final int n = currentBest.getDimension();
        final int npt = numberOfInterpolationPoints;

        final ArrayRealVector glag = new ArrayRealVector(n);
        final ArrayRealVector hcol = new ArrayRealVector(npt);

        final ArrayRealVector work1 = new ArrayRealVector(n);
        final ArrayRealVector work2 = new ArrayRealVector(n);

        for (int k = 0; k < npt; k++) {
            hcol.setEntry(k, 0.0);
        }
        for (int j = 0, max = npt - n - 1; j < max; j++) {
            final double tmp = zMatrix.getEntry(knew, j);
            for (int k = 0; k < npt; k++) {
                hcol.setEntry(k, hcol.getEntry(k) + tmp * zMatrix.getEntry(k, j));
            }
        }
        final double alpha = hcol.getEntry(knew);
        final double ha = 0.5 * alpha;

        // Calculate the gradient of the KNEW-th Lagrange function at XOPT.

        for (int i = 0; i < n; i++) {
            glag.setEntry(i, bMatrix.getEntry(knew, i));
        }
        for (int k = 0; k < npt; k++) {
            double tmp = 0.0;
            for (int j = 0; j < n; j++) {
                tmp += interpolationPoints.getEntry(k, j) * trustRegionCenterOffset.getEntry(j);
            }
            tmp *= hcol.getEntry(k);
            for (int i = 0; i < n; i++) {
                glag.setEntry(i, glag.getEntry(i) + tmp * interpolationPoints.getEntry(k, i));
            }
        }

        // Search for a large denominator along the straight lines through XOPT
        // and another interpolation point. SLBD and SUBD will be lower and upper
        // bounds on the step along each of these lines in turn. PREDSQ will be
        // set to the square of the predicted denominator for each line. PRESAV
        // will be set to the largest admissible value of PREDSQ that occurs.

        double presav = 0.0;
        double step = Double.NaN;
        int ksav = 0;
        int ibdsav = 0;
        double stpsav = 0;
        for (int k = 0; k < npt; k++) {
            if (k == trustRegionCenterInterpolationPointIndex) {
                continue;
            }
            double dderiv = 0.0;
            double distsq = 0.0;
            for (int i = 0; i < n; i++) {
                final double tmp = interpolationPoints.getEntry(k, i) - trustRegionCenterOffset.getEntry(i);
                dderiv += glag.getEntry(i) * tmp;
                distsq += tmp * tmp;
            }
            double subd = adelt / Math.sqrt(distsq);
            double slbd = -subd;
            int ilbd = 0;
            int iubd = 0;
            final double sumin = Math.min(1.0, subd);

            // Revise SLBD and SUBD if necessary because of the bounds in SL and SU.

            for (int i = 0; i < n; i++) {
                final double tmp = interpolationPoints.getEntry(k, i) - trustRegionCenterOffset.getEntry(i);
                if (tmp > 0.0) {
                    if (slbd * tmp < lowerDifference.getEntry(i) - trustRegionCenterOffset.getEntry(i)) {
                        slbd = (lowerDifference.getEntry(i) - trustRegionCenterOffset.getEntry(i)) / tmp;
                        ilbd = -i - 1;
                    }
                    if (subd * tmp > upperDifference.getEntry(i) - trustRegionCenterOffset.getEntry(i)) {
                        subd = Math.max(sumin,
                                        (upperDifference.getEntry(i) - trustRegionCenterOffset.getEntry(i)) / tmp);
                        iubd = i + 1;
                    }
                } else if (tmp < 0.0) {
                    if (slbd * tmp > upperDifference.getEntry(i) - trustRegionCenterOffset.getEntry(i)) {
                        slbd = (upperDifference.getEntry(i) - trustRegionCenterOffset.getEntry(i)) / tmp;
                        ilbd = i + 1;
                    }
                    if (subd * tmp < lowerDifference.getEntry(i) - trustRegionCenterOffset.getEntry(i)) {
                        subd = Math.max(sumin,
                                        (lowerDifference.getEntry(i) - trustRegionCenterOffset.getEntry(i)) / tmp);
                        iubd = -i - 1;
                    }
                }
            }

            // Seek a large modulus of the KNEW-th Lagrange function when the index
            // of the other interpolation point on the line through XOPT is KNEW.

            step = slbd;
            int isbd = ilbd;
            double vlag = Double.NaN;
            if (k == knew) {
                final double diff = dderiv - 1.0;
                vlag = slbd * (dderiv - slbd * diff);
                final double subdTimesFactor = subd * (dderiv - subd * diff);
                if (Math.abs(subdTimesFactor) > Math.abs(vlag)) {
                    step = subd;
                    vlag = subdTimesFactor;
                    isbd = iubd;
                }
                final double halfDderiv = 0.5 * dderiv;
                final double term1 = halfDderiv - diff * slbd;
                final double term2 = halfDderiv - diff * subd;
                if (term1 * term2 < 0.0) {
                    final double d5 = halfDderiv * halfDderiv / diff;
                    if (Math.abs(d5) > Math.abs(vlag)) {
                        step = halfDderiv / diff;
                        vlag = d5;
                        isbd = 0;
                    }
                }

                // Search along each of the other lines through XOPT and another point.

            } else {
                vlag = slbd * (1.0 - slbd);
                final double tmpFactor = subd * (1.0 - subd);
                if (Math.abs(tmpFactor) > Math.abs(vlag)) {
                    step = subd;
                    vlag = tmpFactor;
                    isbd = iubd;
                }
                if (subd > 0.5 && Math.abs(vlag) < 0.25) {
                    step = 0.5;
                    vlag = 0.25;
                    isbd = 0;
                }
                vlag *= dderiv;
            }

            // Calculate PREDSQ for the current line search and maintain PRESAV.

            final double tmp = step * (1.0 - step) * distsq;
            final double predsq = vlag * vlag * (vlag * vlag + ha * tmp * tmp);
            if (predsq > presav) {
                presav = predsq;
                ksav = k;
                stpsav = step;
                ibdsav = isbd;
            }
        }

        // Construct XNEW in a way that satisfies the bound constraints exactly.

        for (int i = 0; i < n; i++) {
            final double tmp = trustRegionCenterOffset.getEntry(i) + stpsav * (interpolationPoints.getEntry(ksav, i) - trustRegionCenterOffset.getEntry(i));
            newPoint.setEntry(i, Math.max(lowerDifference.getEntry(i),
                                      Math.min(upperDifference.getEntry(i), tmp)));
        }
        if (ibdsav < 0) {
            newPoint.setEntry(-ibdsav - 1, lowerDifference.getEntry(-ibdsav - 1));
        }
        if (ibdsav > 0) {
            newPoint.setEntry(ibdsav - 1, upperDifference.getEntry(ibdsav - 1));
        }

        // Prepare for the iterative method that assembles the constrained Cauchy
        // step in W. The sum of squares of the fixed components of W is formed in
        // WFIXSQ, and the free components of W are set to BIGSTP.

        final double bigstp = adelt + adelt;
        int iflag = 0;
        double cauchy = Double.NaN;
        double csave = 0.0;
        while (true) {
            double wfixsq = 0.0;
            double ggfree = 0.0;
            for (int i = 0; i < n; i++) {
                final double glagValue = glag.getEntry(i);
                work1.setEntry(i, 0.0);
                if (Math.min(trustRegionCenterOffset.getEntry(i) - lowerDifference.getEntry(i), glagValue) > 0.0 ||
                    Math.max(trustRegionCenterOffset.getEntry(i) - upperDifference.getEntry(i), glagValue) < 0.0) {
                    work1.setEntry(i, bigstp);
                    ggfree += glagValue * glagValue;
                }
            }
            if (ggfree == 0.0) {
                return new double[] { alpha, 0.0 };
            }

            // Investigate whether more components of W can be fixed.
            final double tmp1 = adelt * adelt - wfixsq;
            if (tmp1 > 0.0) {
                step = Math.sqrt(tmp1 / ggfree);
                ggfree = 0.0;
                for (int i = 0; i < n; i++) {
                    if (work1.getEntry(i) == bigstp) {
                        final double tmp2 = trustRegionCenterOffset.getEntry(i) - step * glag.getEntry(i);
                        if (tmp2 <= lowerDifference.getEntry(i)) {
                            work1.setEntry(i, lowerDifference.getEntry(i) - trustRegionCenterOffset.getEntry(i));
                            wfixsq += work1.getEntry(i) * work1.getEntry(i);
                        } else if (tmp2 >= upperDifference.getEntry(i)) {
                            work1.setEntry(i, upperDifference.getEntry(i) - trustRegionCenterOffset.getEntry(i));
                            wfixsq += work1.getEntry(i) * work1.getEntry(i);
                        } else {
                            ggfree += glag.getEntry(i) * glag.getEntry(i);
                        }
                    }
                }
            }

            // Set the remaining free components of W and all components of XALT,
            // except that W may be scaled later.

            double gw = 0.0;
            for (int i = 0; i < n; i++) {
                final double glagValue = glag.getEntry(i);
                if (work1.getEntry(i) == bigstp) {
                    work1.setEntry(i, -step * glagValue);
                    final double min = Math.min(upperDifference.getEntry(i),
                                                trustRegionCenterOffset.getEntry(i) + work1.getEntry(i));
                    alternativeNewPoint.setEntry(i, Math.max(lowerDifference.getEntry(i), min));
                } else if (work1.getEntry(i) == 0.0) {
                    alternativeNewPoint.setEntry(i, trustRegionCenterOffset.getEntry(i));
                } else if (glagValue > 0.0) {
                    alternativeNewPoint.setEntry(i, lowerDifference.getEntry(i));
                } else {
                    alternativeNewPoint.setEntry(i, upperDifference.getEntry(i));
                }
                gw += glagValue * work1.getEntry(i);
            }

            // Set CURV to the curvature of the KNEW-th Lagrange function along W.
            // Scale W by a factor less than one if that can reduce the modulus of
            // the Lagrange function at XOPT+W. Set CAUCHY to the final value of
            // the square of this function.

            double curv = 0.0;
            for (int k = 0; k < npt; k++) {
                double tmp = 0.0;
                for (int j = 0; j < n; j++) {
                    tmp += interpolationPoints.getEntry(k, j) * work1.getEntry(j);
                }
                curv += hcol.getEntry(k) * tmp * tmp;
            }
            if (iflag == 1) {
                curv = -curv;
            }
            if (curv > -gw && curv < -gw * (1.0 + Math.sqrt(2.0))) {
                final double scale = -gw / curv;
                for (int i = 0; i < n; i++) {
                    final double tmp = trustRegionCenterOffset.getEntry(i) + scale * work1.getEntry(i);
                    alternativeNewPoint.setEntry(i, Math.max(lowerDifference.getEntry(i),
                                              Math.min(upperDifference.getEntry(i), tmp)));
                }
                cauchy = (0.5 * gw * scale) * (0.5 * gw * scale);
            } else {
                cauchy = (gw + 0.5 * curv) * (gw + 0.5 * curv);
            }

            // If IFLAG is zero, then XALT is calculated as before after reversing
            // the sign of GLAG. Thus two XALT vectors become available. The one that
            // is chosen is the one that gives the larger value of CAUCHY.

            if (iflag == 0) {
                for (int i = 0; i < n; i++) {
                    glag.setEntry(i, -glag.getEntry(i));
                    work2.setEntry(i, alternativeNewPoint.getEntry(i));
                }
                csave = cauchy;
                iflag = 1;
            } else {
                break;
            }
        }
        if (csave > cauchy) {
            for (int i = 0; i < n; i++) {
                alternativeNewPoint.setEntry(i, work2.getEntry(i));
            }
            cauchy = csave;
        }

        return new double[] { alpha, cauchy };
    } // altmov

    /**
     * SUBROUTINE PRELIM sets the elements of XBASE, XPT, FVAL, GOPT, HQ, PQ,
     * BMAT and ZMAT for the first iteration, and it maintains the values of
     * NF and KOPT. The vector X is also changed by PRELIM.
     *
     * The arguments N, NPT, X, XL, XU, RHOBEG, IPRINT and MAXFUN are the
     * same as the corresponding arguments in SUBROUTINE BOBYQA.
     * The arguments XBASE, XPT, FVAL, HQ, PQ, BMAT, ZMAT, NDIM, SL and SU
     * are the same as the corresponding arguments in BOBYQB, the elements
     * of SL and SU being set in BOBYQA.
     * GOPT is usually the gradient of the quadratic model at XOPT+XBASE, but
     * it is set by PRELIM to the gradient of the quadratic model at XBASE.
     * If XOPT is nonzero, BOBYQB will change it to its usual value later.
     * NF is maintaned as the number of calls of CALFUN so far.
     * KOPT will be such that the least calculated value of F so far is at
     * the point XPT(KOPT,.)+XBASE in the space of the variables.
     *
     * @param lowerBound Lower bounds.
     * @param upperBound Upper bounds.
     */
    private void prelim(double[] lowerBound,
                        double[] upperBound) {

        final int n = currentBest.getDimension();
        final int npt = numberOfInterpolationPoints;
        final int ndim = bMatrix.getRowDimension();

        final double rhosq = initialTrustRegionRadius * initialTrustRegionRadius;
        final double recip = 1.0 / rhosq;
        final int np = n + 1;

        // Set XBASE to the initial vector of variables, and set the initial
        // elements of XPT, BMAT, HQ, PQ and ZMAT to zero.

        for (int j = 0; j < n; j++) {
            originShift.setEntry(j, currentBest.getEntry(j));
            for (int k = 0; k < npt; k++) {
                interpolationPoints.setEntry(k, j, 0.0);
            }
            for (int i = 0; i < ndim; i++) {
                bMatrix.setEntry(i, j, 0.0);
            }
        }
        for (int i = 0, max = n * np / 2; i < max; i++) {
            modelSecondDerivativesValues.setEntry(i, 0.0);
        }
        for (int k = 0; k < npt; k++) {
            modelSecondDerivativesParameters.setEntry(k, 0.0);
            for (int j = 0, max = npt - np; j < max; j++) {
                zMatrix.setEntry(k, j, 0.0);
            }
        }

        // Begin the initialization procedure. NF becomes one more than the number
        // of function values so far. The coordinates of the displacement of the
        // next initial interpolation point from XBASE are set in XPT(NF+1,.).

        int ipt = 0;
        int jpt = 0;
        double fbeg = Double.NaN;
        do {
            final int nfm = getEvaluations();
            final int nfx = nfm - n;
            final int nfmm = nfm - 1;
            final int nfxm = nfx - 1;
            double stepa = 0;
            double stepb = 0;
            if (nfm <= 2 * n) {
                if (nfm >= 1 && nfm <= n) {
                    stepa = initialTrustRegionRadius;
                    if (upperDifference.getEntry(nfmm) == 0.0) {
                        stepa = -stepa;
                    }
                    interpolationPoints.setEntry(nfm, nfmm, stepa);
                } else if (nfm > n) {
                    stepa = interpolationPoints.getEntry(nfx, nfxm);
                    stepb = -initialTrustRegionRadius;
                    if (lowerDifference.getEntry(nfxm) == 0.0) {
                        stepb = Math.min(2.0 * initialTrustRegionRadius, upperDifference.getEntry(nfxm));
                    }
                    if (upperDifference.getEntry(nfxm) == 0.0) {
                        stepb = Math.max(-2.0 * initialTrustRegionRadius, lowerDifference.getEntry(nfxm));
                    }
                    interpolationPoints.setEntry(nfm, nfxm, stepb);
                }
            } else {
                final int tmp1 = (nfm - np) / n;
                jpt = nfm - tmp1 * n - n;
                ipt = jpt + tmp1;
                if (ipt > n) {
                    final int tmp2 = jpt;
                    jpt = ipt - n;
                    ipt = tmp2;
                }
                final int iptMinus1 = ipt - 1;
                final int jptMinus1 = jpt - 1;
                interpolationPoints.setEntry(nfm, iptMinus1, interpolationPoints.getEntry(ipt, iptMinus1));
                interpolationPoints.setEntry(nfm, jptMinus1, interpolationPoints.getEntry(jpt, jptMinus1));
            }

            // Calculate the next value of F. The least function value so far and
            // its index are required.

            for (int j = 0; j < n; j++) {
                currentBest.setEntry(j, Math.min(Math.max(lowerBound[j],
                                                          originShift.getEntry(j) + interpolationPoints.getEntry(nfm, j)),
                                                 upperBound[j]));
                if (interpolationPoints.getEntry(nfm, j) == lowerDifference.getEntry(j)) {
                    currentBest.setEntry(j, lowerBound[j]);
                }
                if (interpolationPoints.getEntry(nfm, j) == upperDifference.getEntry(j)) {
                    currentBest.setEntry(j, upperBound[j]);
                }
            }

            final double objectiveValue = computeObjectiveValue(currentBest.toArray());
            final double f = isMinimize ? objectiveValue : -objectiveValue;
            final int numEval = getEvaluations(); // nfm + 1
            fAtInterpolationPoints.setEntry(nfm, f);

            if (numEval == 1) {
                fbeg = f;
                trustRegionCenterInterpolationPointIndex = 0;
            } else if (f < fAtInterpolationPoints.getEntry(trustRegionCenterInterpolationPointIndex)) {
                trustRegionCenterInterpolationPointIndex = nfm;
            }

            // Set the nonzero initial elements of BMAT and the quadratic model in the
            // cases when NF is at most 2*N+1. If NF exceeds N+1, then the positions
            // of the NF-th and (NF-N)-th interpolation points may be switched, in
            // order that the function value at the first of them contributes to the
            // off-diagonal second derivative terms of the initial quadratic model.

            if (numEval <= 2 * n + 1) {
                if (numEval >= 2 && numEval <= n + 1) {
                    gradientAtTrustRegionCenter.setEntry(nfmm, (f - fbeg) / stepa);
                    if (npt < numEval + n) {
                        bMatrix.setEntry(0, nfmm, -1.0 / stepa);
                        bMatrix.setEntry(nfm, nfmm, 1.0 / stepa);
                        bMatrix.setEntry(npt + nfmm, nfmm, -0.5 * rhosq);
                    }
                } else if (numEval >= n + 2) {
                    final int ih = nfx * (nfx + 1) / 2 - 1;
                    final double tmp = (f - fbeg) / stepb;
                    final double diff = stepb - stepa;
                    modelSecondDerivativesValues.setEntry(ih, 2.0 * (tmp - gradientAtTrustRegionCenter.getEntry(nfxm)) / diff);
                    gradientAtTrustRegionCenter.setEntry(nfxm, (gradientAtTrustRegionCenter.getEntry(nfxm) * stepb - tmp * stepa) / diff);
                    if (stepa * stepb < 0.0 && f < fAtInterpolationPoints.getEntry(nfm - n)) {
                        fAtInterpolationPoints.setEntry(nfm, fAtInterpolationPoints.getEntry(nfm - n));
                        fAtInterpolationPoints.setEntry(nfm - n, f);
                        if (trustRegionCenterInterpolationPointIndex == nfm) {
                            trustRegionCenterInterpolationPointIndex = nfm - n;
                        }
                        interpolationPoints.setEntry(nfm - n, nfxm, stepb);
                        interpolationPoints.setEntry(nfm, nfxm, stepa);
                    }
                    bMatrix.setEntry(0, nfxm, -(stepa + stepb) / (stepa * stepb));
                    bMatrix.setEntry(nfm, nfxm, -0.5 / interpolationPoints.getEntry(nfm - n, nfxm));
                    bMatrix.setEntry(nfm - n, nfxm,
                                  -bMatrix.getEntry(0, nfxm) - bMatrix.getEntry(nfm, nfxm));
                    zMatrix.setEntry(0, nfxm, Math.sqrt(2.0) / (stepa * stepb));
                    zMatrix.setEntry(nfm, nfxm, Math.sqrt(0.5) / rhosq);
                    zMatrix.setEntry(nfm - n, nfxm,
                                  -zMatrix.getEntry(0, nfxm) - zMatrix.getEntry(nfm, nfxm));
                }

                // Set the off-diagonal second derivatives of the Lagrange functions and
                // the initial quadratic model.

            } else {
                zMatrix.setEntry(0, nfxm, recip);
                zMatrix.setEntry(nfm, nfxm, recip);
                zMatrix.setEntry(ipt, nfxm, -recip);
                zMatrix.setEntry(jpt, nfxm, -recip);

                final int ih = ipt * (ipt - 1) / 2 + jpt - 1;
                final double tmp = interpolationPoints.getEntry(nfm, ipt - 1) * interpolationPoints.getEntry(nfm, jpt - 1);
                modelSecondDerivativesValues.setEntry(ih, (fbeg - fAtInterpolationPoints.getEntry(ipt) - fAtInterpolationPoints.getEntry(jpt) + f) / tmp);
            }
        } while (getEvaluations() < npt);
    } // prelim

    /**
     * A version of the truncated conjugate gradient is applied. If a line
     * search is restricted by a constraint, then the procedure is restarted,
     * the values of the variables that are at their bounds being fixed. If
     * the trust region boundary is reached, then further changes may be made
     * to D, each one being in the two dimensional space that is spanned
     * by the current D and the gradient of Q at XOPT+D, staying on the trust
     * region boundary. Termination occurs when the reduction in Q seems to
     * be close to the greatest reduction that can be achieved.
     * The arguments N, NPT, XPT, XOPT, GOPT, HQ, PQ, SL and SU have the same
     * meanings as the corresponding arguments of BOBYQB.
     * DELTA is the trust region radius for the present calculation, which
     * seeks a small value of the quadratic model within distance DELTA of
     * XOPT subject to the bounds on the variables.
     * XNEW will be set to a new vector of variables that is approximately
     * the one that minimizes the quadratic model within the trust region
     * subject to the SL and SU constraints on the variables. It satisfies
     * as equations the bounds that become active during the calculation.
     * D is the calculated trial step from XOPT, generated iteratively from an
     * initial value of zero. Thus XNEW is XOPT+D after the final iteration.
     * GNEW holds the gradient of the quadratic model at XOPT+D. It is updated
     * when D is updated.
     * xbdi.get( is a working space vector. For I=1,2,...,N, the element xbdi.get((I) is
     * set to -1.0, 0.0, or 1.0, the value being nonzero if and only if the
     * I-th variable has become fixed at a bound, the bound being SL(I) or
     * SU(I) in the case xbdi.get((I)=-1.0 or xbdi.get((I)=1.0, respectively. This
     * information is accumulated during the construction of XNEW.
     * The arrays S, HS and HRED are also used for working space. They hold the
     * current search direction, and the changes in the gradient of Q along S
     * and the reduced D, respectively, where the reduced D is the same as D,
     * except that the components of the fixed variables are zero.
     * DSQ will be set to the square of the length of XNEW-XOPT.
     * CRVMIN is set to zero if D reaches the trust region boundary. Otherwise
     * it is set to the least curvature of H that occurs in the conjugate
     * gradient searches that are not restricted by any constraints. The
     * value CRVMIN=-1.0D0 is set, however, if all of these searches are
     * constrained.
     * @param delta Trust region radius.
     * @param gnew Gradient of the quadratic model at XOPT+D.
     * @param xbdi Working space vector indicating fixed variables.
     * @param s Current search direction.
     * @param hs Change in gradient of Q along S.
     * @param hred Reduced D.
     * @return an array containing dsq and crvmin values.
     */
    private double[] trsbox(
            double delta,
            ArrayRealVector gnew,
            ArrayRealVector xbdi,
            ArrayRealVector s,
            ArrayRealVector hs,
            ArrayRealVector hred
    ) {

        final int n = currentBest.getDimension();
        final int npt = numberOfInterpolationPoints;

        double dsq = Double.NaN;
        double crvmin = Double.NaN;

        // Local variables
        double ds;
        int iu;
        double dhd, dhs, cth, shs, sth, ssq, beta=0.0, sdec, blen;
        int iact = -1;
        int nact = 0;
        double angt = 0.0, qred;
        int isav;
        double tempVal = 0.0, xsav = 0.0, xsum = 0.0, angbd = 0.0, dredg = 0.0, sredg = 0.0;
        int iterc;
        double resid = 0.0, delsq = 0.0, ggsav = 0.0, tempa = 0.0, tempb = 0.0,
        redmax = 0.0, dredsq = 0.0, redsav = 0.0, gredsq = 0.0, rednew = 0.0;
        int itcsav = 0;
        double rdprev = 0.0, rdnext = 0.0, stplen = 0.0, stepsq = 0.0;
        int itermax = 0;

        // The sign of GOPT(I) gives the sign of the change to the I-th variable
        // that will reduce Q from its value at XOPT. Thus xbdi.get((I) shows whether
        // or not to fix the I-th variable at one of its bounds initially, with
        // NACT being set to the number of fixed variables. D and GNEW are also
        // set for the first iteration. DELSQ is the upper bound on the sum of
        // squares of the free variables. QRED is the reduction in Q so far.

        iterc = 0;
        nact = 0;
        for (int i = 0; i < n; i++) {
            xbdi.setEntry(i, 0.0);
            if (trustRegionCenterOffset.getEntry(i) <= lowerDifference.getEntry(i)) {
                if (gradientAtTrustRegionCenter.getEntry(i) >= 0.0) {
                    xbdi.setEntry(i, -1.0);
                }
            } else if (trustRegionCenterOffset.getEntry(i) >= upperDifference.getEntry(i) &&
                    gradientAtTrustRegionCenter.getEntry(i) <= 0.0) {
                xbdi.setEntry(i, 1.0);
            }
            if (xbdi.getEntry(i) != 0.0) {
                ++nact;
            }
            trialStepPoint.setEntry(i, 0.0);
            gnew.setEntry(i, gradientAtTrustRegionCenter.getEntry(i));
        }
        delsq = delta * delta;
        qred = 0.0;
        crvmin = -1.0;

        // Set the next search direction of the conjugate gradient method. It is
        // the steepest descent direction initially and when the iterations are
        // restarted because a variable has just been fixed by a bound, and of
        // course the components of the fixed variables are zero. ITERMAX is an
        // upper bound on the indices of the conjugate gradient iterations.

        int state = 20;
        for(;;) {
            switch (state) {
        case 20: {
            beta = 0.0;
        }
        case 30: {
            stepsq = 0.0;
            for (int i = 0; i < n; i++) {
                if (xbdi.getEntry(i) != 0.0) {
                    s.setEntry(i, 0.0);
                } else if (beta == 0.0) {
                    s.setEntry(i, -gnew.getEntry(i));
                } else {
                    s.setEntry(i, beta * s.getEntry(i) - gnew.getEntry(i));
                }
                stepsq += s.getEntry(i) * s.getEntry(i);
            }
            if (stepsq == 0.0) {
                state = 190; break;
            }
            if (beta == 0.0) {
                gredsq = stepsq;
                itermax = iterc + n - nact;
            }
            if (gredsq * delsq <= qred * 1e-4 * qred) {
                state = 190; break;
            }

            // Multiply the search direction by the second derivative matrix of Q and
            // calculate some scalars for the choice of steplength. Then set BLEN to
            // the length of the the step to the trust region boundary and STPLEN to
            // the steplength, ignoring the simple bounds.

            computeHessianProduct(s, hs);
            state = 50; break;
        }
        case 50: {
            resid = delsq;
            ds = 0.0;
            shs = 0.0;
            for (int i = 0; i < n; i++) {
                if (xbdi.getEntry(i) == 0.0) {
                    resid -= trialStepPoint.getEntry(i) * trialStepPoint.getEntry(i);
                    ds += s.getEntry(i) * trialStepPoint.getEntry(i);
                    shs += s.getEntry(i) * hs.getEntry(i);
                }
            }
            if (resid <= 0.0) {
                state = 90; break;
            }
            tempVal = Math.sqrt(stepsq * resid + ds * ds);
            if (ds < 0.0) {
                blen = (tempVal - ds) / stepsq;
            } else {
                blen = resid / (tempVal + ds);
            }
            stplen = blen;
            if (shs > 0.0) {
                stplen = Math.min(blen, gredsq / shs);
            }

            // Reduce STPLEN if necessary in order to preserve the simple bounds,
            // letting IACT be the index of the new constrained variable.

            iact = -1;
            for (int i = 0; i < n; i++) {
                if (s.getEntry(i) != 0.0) {
                    xsum = trustRegionCenterOffset.getEntry(i) + trialStepPoint.getEntry(i);
                    if (s.getEntry(i) > 0.0) {
                        tempVal = (upperDifference.getEntry(i) - xsum) / s.getEntry(i);
                    } else {
                        tempVal = (lowerDifference.getEntry(i) - xsum) / s.getEntry(i);
                    }
                    if (tempVal < stplen) {
                        stplen = tempVal;
                        iact = i;
                    }
                }
            }

            // Update CRVMIN, GNEW and D. Set SDEC to the decrease that occurs in Q.

            sdec = 0.0;
            if (stplen > 0.0) {
                ++iterc;
                tempVal = shs / stepsq;
                if (iact == -1 && tempVal > 0.0) {
                    if (crvmin == -1.0) {
                        crvmin = tempVal;
                    } else {
                        crvmin = Math.min(crvmin, tempVal);
                    }
                }
                ggsav = gredsq;
                gredsq = 0.0;
                for (int i = 0; i < n; i++) {
                    gnew.setEntry(i, gnew.getEntry(i) + stplen * hs.getEntry(i));
                    if (xbdi.getEntry(i) == 0.0) {
                        gredsq += gnew.getEntry(i) * gnew.getEntry(i);
                    }
                    trialStepPoint.setEntry(i, trialStepPoint.getEntry(i) + stplen * s.getEntry(i));
                }
                sdec = Math.max(stplen * (ggsav - 0.5 * stplen * shs), 0.0);
                qred += sdec;
            }

            // Restart the conjugate gradient method if it has hit a new bound.

            if (iact >= 0) {
                ++nact;
                xbdi.setEntry(iact, 1.0);
                if (s.getEntry(iact) < 0.0) {
                    xbdi.setEntry(iact, -1.0);
                }
                delsq -= trialStepPoint.getEntry(iact) * trialStepPoint.getEntry(iact);
                if (delsq <= 0.0) {
                    state = 190; break;
                }
                state = 20; break;
            }

            // If STPLEN is less than BLEN, then either apply another conjugate
            // gradient iteration or RETURN.

            if (stplen < blen) {
                if (iterc == itermax) {
                    state = 190; break;
                }
                if (sdec <= qred * 0.01) {
                    state = 190; break;
                }
                beta = gredsq / ggsav;
                state = 30; break;
            }
        }
        case 90: {
            crvmin = 0.0;

            // Prepare for the alternative iteration by calculating some scalars
            // and by multiplying the reduced D by the second derivative matrix of
            // Q, where S holds the reduced D in the call of GGMULT.

        }
        case 100: {
            if (nact >= n - 1) {
                state = 190; break;
            }
            dredsq = 0.0;
            dredg = 0.0;
            gredsq = 0.0;
            for (int i = 0; i < n; i++) {
                if (xbdi.getEntry(i) == 0.0) {
                    dredsq += trialStepPoint.getEntry(i) * trialStepPoint.getEntry(i);
                    dredg += trialStepPoint.getEntry(i) * gnew.getEntry(i);
                    gredsq += gnew.getEntry(i) * gnew.getEntry(i);
                    s.setEntry(i, trialStepPoint.getEntry(i));
                } else {
                    s.setEntry(i, 0.0);
                }
            }
            itcsav = iterc;
            computeHessianProduct(s, hred);
            state = 120; break;
            // Let the search direction S be a linear combination of the reduced D
            // and the reduced G that is orthogonal to the reduced D.
        }
        case 120: {
            ++iterc;
            tempVal = gredsq * dredsq - dredg * dredg;
            if (tempVal <= qred * 1e-4 * qred) {
                state = 190; break;
            }
            tempVal = Math.sqrt(tempVal);
            for (int i = 0; i < n; i++) {
                if (xbdi.getEntry(i) == 0.0) {
                    s.setEntry(i, (dredg * trialStepPoint.getEntry(i) - dredsq * gnew.getEntry(i)) / tempVal);
                } else {
                    s.setEntry(i, 0.0);
                }
            }
            sredg = -tempVal;

            // By considering the simple bounds on the variables, calculate an upper
            // bound on the tangent of half the angle of the alternative iteration,
            // namely ANGBD, except that, if already a free variable has reached a
            // bound, there is a branch back to label 100 after fixing that variable.

            angbd = 1.0;
            iact = -1;
            for (int i = 0; i < n; i++) {
                if (xbdi.getEntry(i) == 0.0) {
                    tempa = trustRegionCenterOffset.getEntry(i) + trialStepPoint.getEntry(i) - lowerDifference.getEntry(i);
                    tempb = upperDifference.getEntry(i) - trustRegionCenterOffset.getEntry(i) - trialStepPoint.getEntry(i);
                    if (tempa <= 0.0) {
                        ++nact;
                        xbdi.setEntry(i, -1.0);
                        state = 100; break;
                    } else if (tempb <= 0.0) {
                        ++nact;
                        xbdi.setEntry(i, 1.0);
                        state = 100; break;
                    }
                    ssq = trialStepPoint.getEntry(i) * trialStepPoint.getEntry(i) + s.getEntry(i) * s.getEntry(i);
                    double lowerBoundDistSq = (trustRegionCenterOffset.getEntry(i) - lowerDifference.getEntry(i));
                    lowerBoundDistSq *= lowerBoundDistSq;
                    double tempCalc = ssq - lowerBoundDistSq;
                    if (tempCalc > 0.0) {
                        tempCalc = Math.sqrt(tempCalc) - s.getEntry(i);
                        if (angbd * tempCalc > tempa) {
                            angbd = tempa / tempCalc;
                            iact = i;
                            xsav = -1.0;
                        }
                    }
                    double upperBoundDistSq = (upperDifference.getEntry(i) - trustRegionCenterOffset.getEntry(i));
                    upperBoundDistSq *= upperBoundDistSq;
                    tempCalc = ssq - upperBoundDistSq;
                    if (tempCalc > 0.0) {
                        tempCalc = Math.sqrt(tempCalc) + s.getEntry(i);
                        if (angbd * tempCalc > tempb) {
                            angbd = tempb / tempCalc;
                            iact = i;
                            xsav = 1.0;
                        }
                    }
                }
            }
            if (iact != -1 && (state == 100 || state == 150)) { /* If a bound was hit and state changed */ break; /* Skip computeHessianProduct again */} // Added a check to avoid redundant computeHessianProduct calls if iact caused a state change.
            computeHessianProduct(s, hs);
            state = 150; break;
        }
        case 150: {
            shs = 0.0;
            dhs = 0.0;
            dhd = 0.0;
            for (int i = 0; i < n; i++) {
                if (xbdi.getEntry(i) == 0.0) {
                    shs += s.getEntry(i) * hs.getEntry(i);
                    dhs += trialStepPoint.getEntry(i) * hs.getEntry(i);
                    dhd += trialStepPoint.getEntry(i) * hred.getEntry(i);
                }
            }

            // Seek the greatest reduction in Q for a range of equally spaced values
            // of ANGT in [0,ANGBD], where ANGT is the tangent of half the angle of
            // the alternative iteration.

            redmax = 0.0;
            isav = -1;
            redsav = 0.0;
            iu = (int) (angbd * 17.0 + 3.1);
            for (int i = 0; i < iu; i++) {
                angt = angbd * i / iu;
                sth = (angt + angt) / (1.0 + angt * angt);
                tempVal = shs + angt * (angt * dhd - dhs - dhs);
                rednew = sth * (angt * dredg - sredg - 0.5 * sth * tempVal);
                if (rednew > redmax) {
                    redmax = rednew;
                    isav = i;
                    rdprev = redsav;
                } else if (i == isav + 1) {
                    rdnext = rednew;
                }
                redsav = rednew;
            }

            // Return if the reduction is zero. Otherwise, set the sine and cosine
            // of the angle of the alternative iteration, and calculate SDEC.

            if (isav < 0) {
                state = 190; break;
            }
            if (isav < iu) {
                tempVal = (rdnext - rdprev) / (redmax + redmax - rdprev - rdnext);
                angt = angbd * (isav + 0.5 * tempVal) / iu;
            }
            cth = (1.0 - angt * angt) / (1.0 + angt * angt);
            sth = (angt + angt) / (1.0 + angt * angt);
            tempVal = shs + angt * (angt * dhd - dhs - dhs);
            sdec = sth * (angt * dredg - sredg - 0.5 * sth * tempVal);
            if (sdec <= 0.0) {
                state = 190; break;
            }

            // Update GNEW, D and HRED. If the angle of the alternative iteration
            // is restricted by a bound on a free variable, that variable is fixed
            // at the bound.

            dredg = 0.0;
            gredsq = 0.0;
            for (int i = 0; i < n; i++) {
                gnew.setEntry(i, gnew.getEntry(i) + (cth - 1.0) * hred.getEntry(i) + sth * hs.getEntry(i));
                if (xbdi.getEntry(i) == 0.0) {
                    trialStepPoint.setEntry(i, cth * trialStepPoint.getEntry(i) + sth * s.getEntry(i));
                    dredg += trialStepPoint.getEntry(i) * gnew.getEntry(i);
                    gredsq += gnew.getEntry(i) * gnew.getEntry(i);
                }
                hred.setEntry(i, cth * hred.getEntry(i) + sth * hs.getEntry(i));
            }
            qred += sdec;
            if (iact >= 0 && isav == iu) {
                ++nact;
                xbdi.setEntry(iact, xsav);
                state = 100; break;
            }

            // If SDEC is sufficiently small, then RETURN after setting XNEW to
            // XOPT+D, giving careful attention to the bounds.

            if (sdec > qred * 0.01) {
                state = 120; break;
            }
        }
        case 190: {
            dsq = 0.0;
            for (int i = 0; i < n; i++) {
                final double minVal = Math.min(trustRegionCenterOffset.getEntry(i) + trialStepPoint.getEntry(i),
                                            upperDifference.getEntry(i));
                newPoint.setEntry(i, Math.max(minVal, lowerDifference.getEntry(i)));
                if (xbdi.getEntry(i) == -1.0) {
                    newPoint.setEntry(i, lowerDifference.getEntry(i));
                }
                if (xbdi.getEntry(i) == 1.0) {
                    newPoint.setEntry(i, upperDifference.getEntry(i));
                }
                trialStepPoint.setEntry(i, newPoint.getEntry(i) - trustRegionCenterOffset.getEntry(i));
                dsq += trialStepPoint.getEntry(i) * trialStepPoint.getEntry(i);
            }
            return new double[] { dsq, crvmin };
        }
        default: {
            throw new MathIllegalStateException(LocalizedFormats.SIMPLE_MESSAGE, "trsbox");
        }}
        }
    } // trsbox

    /**
     * The following instructions multiply the current S-vector by the second
     * derivative matrix of the quadratic model, putting the product in HS.
     * They are reached from three different parts of the software above and
     * they can be regarded as an external subroutine.
     *
     * @param s The current search direction vector.
     * @param hs The resulting product vector (H*s).
     */
    private void computeHessianProduct(ArrayRealVector s, ArrayRealVector hs) {
        final int n = currentBest.getDimension();
        final int npt = numberOfInterpolationPoints;

        int ih = 0;
        for (int j = 0; j < n; j++) {
            hs.setEntry(j, 0.0);
            for (int i = 0; i <= j; i++) {
                if (i < j) {
                    hs.setEntry(j, hs.getEntry(j) + modelSecondDerivativesValues.getEntry(ih) * s.getEntry(i));
                }
                hs.setEntry(i, hs.getEntry(i) + modelSecondDerivativesValues.getEntry(ih) * s.getEntry(j));
                ih++;
            }
        }
        final RealVector tmpProduct = interpolationPoints.operate(s).ebeMultiply(modelSecondDerivativesParameters);
        for (int k = 0; k < npt; k++) {
            if (modelSecondDerivativesParameters.getEntry(k) != 0.0) {
                for (int i = 0; i < n; i++) {
                    hs.setEntry(i, hs.getEntry(i) + tmpProduct.getEntry(k) * interpolationPoints.getEntry(k, i));
                }
            }
        }
    }

    /**
     * The arrays BMAT and ZMAT are updated, as required by the new position
     * of the interpolation point that has the index KNEW. The vector VLAG has
     * N+NPT components, set on entry to the first NPT and last N components
     * of the product Hw in equation (4.11) of the Powell (2006) paper on
     * NEWUOA. Further, BETA is set on entry to the value of the parameter
     * with that name, and DENOM is set to the denominator of the updating
     * formula. Elements of ZMAT may be treated as zero if their moduli are
     * at most ZTEST. The first NDIM elements of W are used for working space.
     * @param beta The beta parameter.
     * @param denom The denominator of the updating formula.
     * @param knew The index of the interpolation point that has moved.
     */
    private void update(
            double beta,
            double denom,
            int knew
    ) {

        final int n = currentBest.getDimension();
        final int npt = numberOfInterpolationPoints;
        final int nptm = npt - n - 1;

        // XXX Should probably be split into two arrays. (Comment from original)
        final ArrayRealVector work = new ArrayRealVector(npt + n);

        double ztest = 0.0;
        for (int k = 0; k < npt; k++) {
            for (int j = 0; j < nptm; j++) {
                ztest = Math.max(ztest, Math.abs(zMatrix.getEntry(k, j)));
            }
        }
        ztest *= 1e-20;

        // Apply the rotations that put zeros in the KNEW-th row of ZMAT.

        for (int j = 1; j < nptm; j++) {
            final double zEntryKnewJ = zMatrix.getEntry(knew, j);
            if (Math.abs(zEntryKnewJ) > ztest) {
                final double zEntryKnew0 = zMatrix.getEntry(knew, 0);
                final double d4 = Math.sqrt(zEntryKnew0 * zEntryKnew0 + zEntryKnewJ * zEntryKnewJ);
                final double cosTheta = zEntryKnew0 / d4;
                final double sinTheta = zEntryKnewJ / d4;
                for (int i = 0; i < npt; i++) {
                    final double tempZ0 = cosTheta * zMatrix.getEntry(i, 0) + sinTheta * zMatrix.getEntry(i, j);
                    zMatrix.setEntry(i, j, cosTheta * zMatrix.getEntry(i, j) - sinTheta * zMatrix.getEntry(i, 0));
                    zMatrix.setEntry(i, 0, tempZ0);
                }
            }
            zMatrix.setEntry(knew, j, 0.0);
        }

        // Put the first NPT components of the KNEW-th column of HLAG into W,
        // and calculate the parameters of the updating formula.

        for (int i = 0; i < npt; i++) {
            work.setEntry(i, zMatrix.getEntry(knew, 0) * zMatrix.getEntry(i, 0));
        }
        final double alpha = work.getEntry(knew);
        final double tau = lagrangeValuesAtNewPoint.getEntry(knew);
        lagrangeValuesAtNewPoint.setEntry(knew, lagrangeValuesAtNewPoint.getEntry(knew) - 1.0);

        // Complete the updating of ZMAT.

        final double sqrtDenom = Math.sqrt(denom);
        final double factor1 = tau / sqrtDenom;
        final double factor2 = zMatrix.getEntry(knew, 0) / sqrtDenom;
        for (int i = 0; i < npt; i++) {
            zMatrix.setEntry(i, 0,
                          factor1 * zMatrix.getEntry(i, 0) - factor2 * lagrangeValuesAtNewPoint.getEntry(i));
        }

        // Finally, update the matrix BMAT.

        for (int j = 0; j < n; j++) {
            final int jp = npt + j;
            work.setEntry(jp, bMatrix.getEntry(knew, j));
            final double d3 = (alpha * lagrangeValuesAtNewPoint.getEntry(jp) - tau * work.getEntry(jp)) / denom;
            final double d4 = (-beta * work.getEntry(jp) - tau * lagrangeValuesAtNewPoint.getEntry(jp)) / denom;
            for (int i = 0; i <= jp; i++) {
                bMatrix.setEntry(i, j,
                              bMatrix.getEntry(i, j) + d3 * lagrangeValuesAtNewPoint.getEntry(i) + d4 * work.getEntry(i));
                if (i >= npt) {
                    bMatrix.setEntry(jp, (i - npt), bMatrix.getEntry(i, j));
                }
            }
        }
    } // update

    /**
     * Performs validity checks.
     *
     * @param lowerBound Lower bounds (constraints) of the objective variables.
     * @param upperBound Upperer bounds (constraints) of the objective variables.
     */
    private void setup(double[] lowerBound,
                       double[] upperBound) {

        double[] init = getStartPoint();
        final int dimension = init.length;

        // Check problem dimension.
        if (dimension < MINIMUM_PROBLEM_DIMENSION) {
            throw new NumberIsTooSmallException(dimension, MINIMUM_PROBLEM_DIMENSION, true);
        }
        // Check number of interpolation points.
        final int[] nPointsInterval = { dimension + 2, (dimension + 2) * (dimension + 1) / 2 };
        if (numberOfInterpolationPoints < nPointsInterval[0] ||
            numberOfInterpolationPoints > nPointsInterval[1]) {
            throw new OutOfRangeException(LocalizedFormats.NUMBER_OF_INTERPOLATION_POINTS,
                                          numberOfInterpolationPoints,
                                          nPointsInterval[0],
                                          nPointsInterval[1]);
        }

        // Initialize bound differences.
        boundDifference = new double[dimension];

        double requiredMinDiff = 2 * initialTrustRegionRadius;
        double minDiff = Double.POSITIVE_INFINITY;
        for (int i = 0; i < dimension; i++) {
            boundDifference[i] = upperBound[i] - lowerBound[i];
            minDiff = Math.min(minDiff, boundDifference[i]);
        }
        if (minDiff < requiredMinDiff) {
            initialTrustRegionRadius = minDiff / 3.0;
        }

        // Initialize the data structures used by the "bobyqa" method.
        bMatrix = new Array2DRowRealMatrix(dimension + numberOfInterpolationPoints,
                                           dimension);
        zMatrix = new Array2DRowRealMatrix(numberOfInterpolationPoints,
                                           numberOfInterpolationPoints - dimension - 1);
        interpolationPoints = new Array2DRowRealMatrix(numberOfInterpolationPoints,
                                                       dimension);
        originShift = new ArrayRealVector(dimension);
        fAtInterpolationPoints = new ArrayRealVector(numberOfInterpolationPoints);
        trustRegionCenterOffset = new ArrayRealVector(dimension);
        gradientAtTrustRegionCenter = new ArrayRealVector(dimension);
        lowerDifference = new ArrayRealVector(dimension);
        upperDifference = new ArrayRealVector(dimension);
        modelSecondDerivativesParameters = new ArrayRealVector(numberOfInterpolationPoints);
        newPoint = new ArrayRealVector(dimension);
        alternativeNewPoint = new ArrayRealVector(dimension);
        trialStepPoint = new ArrayRealVector(dimension);
        lagrangeValuesAtNewPoint = new ArrayRealVector(dimension + numberOfInterpolationPoints);
        modelSecondDerivativesValues = new ArrayRealVector(dimension * (dimension + 1) / 2);
    }
}
//CHECKSTYLE: resume all
