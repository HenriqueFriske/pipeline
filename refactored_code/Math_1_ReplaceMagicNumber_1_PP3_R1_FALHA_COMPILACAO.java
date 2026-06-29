/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license license agreements.  See the NOTICE file distributed with
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
package org.apache.commons.math3.util;

import java.io.PrintStream;

/**
 * Faster, more accurate, portable alternative to {@link Math} and
 * {@link StrictMath} for large scale computation.
 * <p>
 * FastMath is a drop-in replacement for both Math and StrictMath. This
 * means that for any method in Math (say {@code Math.sin(x)} or
 * {@code Math.cbrt(y)}), user can directly change the class and use the
 * methods as is (using {@code FastMath.sin(x)} or {@code FastMath.cbrt(y)}
 * in the previous example).
 * </p>
 * <p>
 * FastMath speed is achieved by relying heavily on optimizing compilers
 * to native code present in many JVMs today and use of large tables.
 * The larger tables are lazily initialised on first use, so that the setup
 * time does not penalise methods that don't need them.
 * </p>
 * <p>
 * Note that FastMath is
 * extensively used inside Apache Commons Math, so by calling some algorithms,
 * the overhead when the the tables need to be intialised will occur
 * regardless of the end-user calling FastMath methods directly or not.
 * Performance figures for a specific JVM and hardware can be evaluated by
 * running the FastMathTestPerformance tests in the test directory of the source
 * distribution.
 * </p>
 * <p>
 * FastMath accuracy should be mostly independent of the JVM as it relies only
 * on IEEE-754 basic operations and on embedded tables. Almost all operations
 * are accurate to about 0.5 ulp throughout the domain range. This statement,
 * of course is only a rough global observed behavior, it is <em>not</em> a
 * guarantee for <em>every</em> double numbers input (see William Kahan's <a
 * href="http://en.wikipedia.org/wiki/Rounding#The_table-maker.27s_dilemma">Table
 * Maker's Dilemma</a>).
 * </p>
 * <p>
 * FastMath additionally implements the following methods not found in Math/StrictMath:
 * <ul>
 * <li>{@link #asinh(double)}</li>
 * <li>{@link #acosh(double)}</li>
 * <li>{@link #atanh(double)}</li>
 * </ul>
 * The following methods are found in Math/StrictMath since 1.6 only, they are provided
 * by FastMath even in 1.5 Java virtual machines
 * <ul>
 * <li>{@link #copySign(double, double)}</li>
 * <li>{@link #getExponent(double)}</li>
 * <li>{@link #nextAfter(double,double)}</li>
 * <li>{@link #nextUp(double)}</li>
 * <li>{@link #scalb(double, int)}</li>
 * <li>{@link #copySign(float, float)}</li>
 * <li>{@link #getExponent(float)}</li>
 * <li>{@link #nextAfter(float,double)}</li>
 * <li>{@link #nextUp(float)}</li>
 * <li>{@link #scalb(float, int)}</li>
 * </ul>
 * </p>
 * @version $Id$
 * @since 2.2
 */
public class FastMath {
    /** Archimede's constant PI, ratio of circle circumference to diameter. */
    public static final double PI = 105414357.0 / 33554432.0 + 1.984187159361080883e-9;

    /** Napier's constant e, base of the natural logarithm. */
    public static final double E = 2850325.0 / 1048576.0 + 8.254840070411028747e-8;

    /** Index of exp(0) in the array of integer exponentials. */
    static final int EXP_INT_TABLE_MAX_INDEX = 750;
    /** Length of the array of integer exponentials. */
    static final int EXP_INT_TABLE_LEN = EXP_INT_TABLE_MAX_INDEX * 2;
    /** Logarithm table length. */
    static final int LN_MANT_LEN = 1024;
    /** Exponential fractions table length. */
    static final int EXP_FRAC_TABLE_LEN = 1025; // 0, 1/1024, ... 1024/1024

    /** StrictMath.log(Double.MAX_VALUE): {@value} */
    private static final double LOG_MAX_VALUE = StrictMath.log(Double.MAX_VALUE);

    /** Indicator for tables initialization.
     * <p>
     * This compile-time constant should be set to true only if one explicitly
     * wants to compute the tables at class loading time instead of using the
     * already computed ones provided as literal arrays below.
     * </p>
     */
    private static final boolean RECOMPUTE_TABLES_AT_RUNTIME = false;

    /** log(2) (high bits). */
    private static final double LN_2_A = 0.693147063255310059;

    /** log(2) (low bits). */
    private static final double LN_2_B = 1.17304635250823482e-7;

    /** Coefficients for log, when input 0.99 < x < 1.01. */
    private static final double LN_QUICK_COEF[][] = {
        {1.0, 5.669184079525E-24},
        {-0.25, -0.25},
        {0.3333333134651184, 1.986821492305628E-8},
        {-0.25, -6.663542893624021E-14},
        {0.19999998807907104, 1.1921056801463227E-8},
        {-0.1666666567325592, -7.800414592973399E-9},
        {0.1428571343421936, 5.650007086920087E-9},
        {-0.12502530217170715, -7.44321345601866E-11},
        {0.11113807559013367, 9.219544613762692E-9},
    };

    /** Coefficients for log in the range of 1.0 < x < 1.0 + 2^-10. */
    private static final double LN_HI_PREC_COEF[][] = {
        {1.0, -6.032174644509064E-23},
        {-0.25, -0.25},
        {0.3333333134651184, 1.9868161777724352E-8},
        {-0.2499999701976776, -2.957007209750105E-8},
        {0.19999954104423523, 1.5830993332061267E-10},
        {-0.16624879837036133, -2.6033824355191673E-8}
    };

    /** Sine, Cosine, Tangent tables are for 0, 1/8, 2/8, ... 13/8 = PI/2 approx. */
    private static final int SINE_TABLE_LEN = 14;

    /** Sine table (high bits). */
    private static final double SINE_TABLE_A[] =
        {
        +0.0d,
        +0.1246747374534607d,
        +0.24740394949913025d,
        +0.366272509098053d,
        +0.4794255495071411d,
        +0.5850973129272461d,
        +0.6816387176513672d,
        +0.7675435543060303d,
        +0.8414709568023682d,
        +0.902267575263977d,
        +0.9489846229553223d,
        +0.9808930158615112d,
        +0.9974949359893799d,
        +0.9985313415527344d,
    };

    /** Sine table (low bits). */
    private static final double SINE_TABLE_B[] =
        {
        +0.0d,
        -4.068233003401932E-9d,
        +9.755392680573412E-9d,
        +1.9987994582857286E-8d,
        -1.0902938113007961E-8d,
        -3.9986783938944604E-8d,
        +4.23719669792332E-8d,
        -5.207000323380292E-8d,
        +2.800552834259E-8d,
        +1.883511811213715E-8d,
        -3.5997360512765566E-9d,
        +4.116164446561962E-8d,
        +5.0614674548127384E-8d,
        -1.0129027912496858E-9d,
    };

    /** Cosine table (high bits). */
    private static final double COSINE_TABLE_A[] =
        {
        +1.0d,
        +0.9921976327896118d,
        +0.9689123630523682d,
        +0.9305076599121094d,
        +0.8775825500488281d,
        +0.8109631538391113d,
        +0.7316888570785522d,
        +0.6409968137741089d,
        +0.5403022766113281d,
        +0.4311765432357788d,
        +0.3153223395347595d,
        +0.19454771280288696d,
        +0.07073719799518585d,
        -0.05417713522911072d,
    };

    /** Cosine table (low bits). */
    private static final double COSINE_TABLE_B[] =
        {
        +0.0d,
        +3.4439717236742845E-8d,
        +5.865827662008209E-8d,
        -3.7999795083850525E-8d,
        +1.184154459111628E-8d,
        -3.43338934259355E-8d,
        +1.1795268640216787E-8d,
        +4.438921624363781E-8d,
        +2.925681159240093E-8d,
        -2.6437112632041807E-8d,
        +2.2860509143963117E-8d,
        -4.813899778443457E-9d,
        +3.6725170580355583E-9d,
        +2.0217439756338078E-10d,
    };


    /** Tangent table, used by atan() (high bits). */
    private static final double TANGENT_TABLE_A[] =
        {
        +0.0d,
        +0.1256551444530487d,
        +0.25534194707870483d,
        +0.3936265707015991d,
        +0.5463024377822876d,
        +0.7214844226837158d,
        +0.9315965175628662d,
        +1.1974215507507324d,
        +1.5574076175689697d,
        +2.092571258544922d,
        +3.0095696449279785d,
        +5.041914939880371d,
        +14.101419448852539d,
        -18.430862426757812d,
    };

    /** Tangent table, used by atan() (low bits). */
    private static final double TANGENT_TABLE_B[] =
        {
        +0.0d,
        -7.877917738262007E-9d,
        -2.5857668567479893E-8d,
        +5.2240336371356666E-9d,
        +5.206150291559893E-8d,
        +1.8307188599677033E-8d,
        -5.7618793749770706E-8d,
        +7.848361555046424E-8d,
        +1.0708593250394448E-7d,
        +1.7827257129423813E-8d,
        +2.893485277253286E-8d,
        +3.1660099222737955E-7d,
        +4.983191803254889E-7d,
        -3.356118100840571E-7d,
    };

    /** Bits of 1/(2*pi), need for reducePayneHanek(). */
    private static final long RECIP_2PI[] = new long[] {
        (0x28be60dbL << 32) | 0x9391054aL,
        (0x7f09d5f4L << 32) | 0x7d4d3770L,
        (0x36d8a566L << 32) | 0x4f10e410L,
        (0x7f9458eaL << 32) | 0xf7aef158L,
        (0x6dc91b8eL << 32) | 0x909374b8L,
        (0x01924bbaL << 32) | 0x82746487L,
        (0x3f877ac7L << 32) | 0x2c4a69cfL,
        (0xba208d7dL << 32) | 0x4baed121L,
        (0x3a671c09L << 32) | 0xad17df90L,
        (0x4e64758eL << 32) | 0x60d4ce7dL,
        (0x272117e2L << 32) | 0xef7e4a0eL,
        (0xc7fe25ffL << 32) | 0xf7816603L,
        (0xfbcbc462L << 32) | 0xd6829b47L,
        (0xdb4d9fb3L << 32) | 0xc9f2c26dL,
        (0xd3d18fd9L << 32) | 0xa797fa8bL,
        (0x5d49eeb1L << 32) | 0xfaf97c5eL,
        (0xcf41ce7dL << 32) | 0xe294a4baL,
         0x9afed7ecL << 32  };

    /** Bits of pi/4, need for reducePayneHanek(). */
    private static final long PI_O_4_BITS[] = new long[] {
        (0xc90fdaa2L << 32) | 0x2168c234L,
        (0xc4c6628bL << 32) | 0x80dc1cd1L };

    /** Eighths.
     * This is used by sinQ, because its faster to do a table lookup than
     * a multiply in this time-critical routine
     */
    private static final double EIGHTHS[] = {0, 0.125, 0.25, 0.375, 0.5, 0.625, 0.75, 0.875, 1.0, 1.125, 1.25, 1.375, 1.5, 1.625};

    /** Table of 2^((n+2)/3) */
    private static final double CBRTTWO[] = { 0.6299605249474366,
                                            0.7937005259840998,
                                            1.0,
                                            1.2599210498948732,
                                            1.5874010519681994 };

    /*
     *  There are 52 bits in the mantissa of a double.
     *  For additional precision, the code splits double numbers into two parts,
     *  by clearing the low order 30 bits if possible, and then performs the arithmetic
     *  on each half separately.
     */

    /**
     * 0x40000000 - used to split a double into two parts, both with the low order bits cleared.
     * Equivalent to 2^30.
     */
    private static final long HEX_40000000 = 0x40000000L; // 1073741824L

    /** Mask used to clear low order 30 bits */
    private static final long MASK_30BITS = -1L - (HEX_40000000 -1); // 0xFFFFFFFFC0000000L;

    /** Mask used to clear the non-sign part of an int. */
    private static final int MASK_NON_SIGN_INT = 0x7fffffff;

    /** Mask used to clear the non-sign part of a long. */
    private static final long MASK_NON_SIGN_LONG = 0x7fffffffffffffffl;

    /** 2^52 - double numbers this large must be integral (no fraction) or NaN or Infinite */
    private static final double TWO_POWER_52 = 4503599627370496.0;
    /** 2^53 - double numbers this large must be even. */
    private static final double TWO_POWER_53 = 2 * TWO_POWER_52;

    /** Constant: {@value}. */
    private static final double F_1_3 = 1d / 3d;
    /** Constant: {@value}. */
    private static final double F_1_5 = 1d / 5d;
    /** Constant: {@value}. */
    private static final double F_1_7 = 1d / 7d;
    /** Constant: {@value}. */
    private static final double F_1_9 = 1d / 9d;
    /** Constant: {@value}. */
    private static final double F_1_11 = 1d / 11d;
    /** Constant: {@value}. */
    private static final double F_1_13 = 1d / 13d;
    /** Constant: {@value}. */
    private static final double F_1_15 = 1d / 15d;
    /** Constant: {@value}. */
    private static final double F_1_17 = 1d / 17d;
    /** Constant: {@value}. */
    private static final double F_3_4 = 3d / 4d;
    /** Constant: {@value}. */
    private static final double F_15_16 = 15d / 16d;
    /** Constant: {@value}. */
    private static final double F_13_14 = 13d / 14d;
    /** Constant: {@value}. */
    private static final double F_11_12 = 11d / 12d;
    /** Constant: {@value}. */
    private static final double F_9_10 = 9d / 10d;
    /** Constant: {@value}. */
    private static final double F_7_8 = 7d / 8d;
    /** Constant: {@value}. */
    private static final double F_5_6 = 5d / 6d;
    /** Constant: {@value}. */
    private static final double F_1_2 = 1d / 2d;
    /** Constant: {@value}. */
    private static final double F_1_4 = 1d / 4d;

    /**
     * Private Constructor
     */
    private FastMath() {}

    // Helper methods for double-double arithmetic to improve readability

    /**
     * Splits a double value into its high and low parts for double-double arithmetic.
     * The high part has its low 30 bits cleared if possible to ensure clean separation.
     *
     * @param value The double to split.
     * @return A two-element array: [high_part, low_part].
     */
    private static double[] splitDouble(final double value) {
        // Handle subnormal or zero explicitly to avoid issues with HEX_40000000 multiplication
        if (value > -Precision.SAFE_MIN && value < Precision.SAFE_MIN) {
            return new double[]{value, 0.0};
        }
        // Original doubleHighPart logic: clear low-order bits for clean split
        long xl = Double.doubleToRawLongBits(value);
        xl = xl & MASK_30BITS;
        double high = Double.longBitsToDouble(xl);
        double low = value - high;
        return new double[]{high, low};
    }

    /**
     * Performs high-precision addition of two double-double numbers (aHigh, aLow) and (bHigh, bLow).
     * The result is returned as a new double-double array [sumHigh, sumLow].
     * This uses a standard error-free transformation for addition (Shewchuk's algorithm or similar).
     */
    private static double[] addDoubleDouble(double aHigh, double aLow, double bHigh, double bLow) {
        double sum = aHigh + bHigh;
        double v = sum - aHigh;
        double error = (aHigh - (sum - v)) + (bHigh - v) + aLow + bLow;
        // Renormalize sum and error
        double resultHigh = sum + error;
        double resultLow = -(resultHigh - sum - error);
        return new double[]{resultHigh, resultLow};
    }

    /**
     * Computes the high-precision reciprocal of a double-double number (numHigh, numLow).
     * Returns the reciprocal as a double-double array [recipHigh, recipLow].
     */
    private static double[] computeDoubleDoubleReciprocal(double numHigh, double numLow) {
        double recipEstimate = 1.0 / numHigh;
        double[] recipSplit = splitDouble(recipEstimate);
        double recipA = recipSplit[0]; // High part of reciprocal
        double recipB = recipSplit[1]; // Low part of reciprocal

        // Get higher precision parts of numHigh for error calculation
        double[] numHighSplit = splitDouble(numHigh);
        double numHH = numHighSplit[0]; // High part of numHigh
        double numHL = numHighSplit[1]; // Low part of numHigh

        // Correction term based on 1 - (num * recip_estimate)
        double prodHH = numHH * recipA;
        double prodHL = numHH * recipB + numHL * recipA + numHL * recipB;
        double correction = 1.0 - (prodHH + prodHL); // Approximation of 1 - (numHigh * recipEstimate)

        recipB += correction * recipEstimate; // Scale correction by recipEstimate
        recipB += -numLow * recipEstimate * recipEstimate; // Account for numLow in original number

        return new double[]{recipA, recipB};
    }

    /**
     * Applies a specific error correction for division (y/x) using expanded precision.
     * This matches the pattern found in `atan2` and `asin`/`acos` for refining a quotient.
     *
     * @param yHigh The high part of the dividend.
     * @param yLow The low part of the dividend.
     * @param xHigh The high part of the divisor.
     * @param xLow The low part of the divisor.
     * @param currentRatioHigh The current high-precision estimate of y/x.
     * @param currentRatioLow The current low-precision error term for y/x.
     * @return The refined low-precision error term for the quotient.
     */
    private static double computeDivisionCorrection(double yHigh, double yLow, double xHigh, double xLow, double currentRatioHigh, double currentRatioLow) {
        double[] xSplit = splitDouble(xHigh); // Splitting xHigh into even higher precision for error calculation
        double xHH = xSplit[0];
        double xHL = xSplit[1];

        // The main correction term for (y - ratio * x) / x
        double errorTerm = (yHigh - currentRatioHigh * xHH - currentRatioHigh * xHL - currentRatioLow * xHH - currentRatioLow * xHL) / xHigh;
        errorTerm += yLow / xHigh; // Add effect of yLow
        errorTerm += -yHigh * xLow / xHigh / xHigh; // Add effect of xLow

        return errorTerm;
    }


    // Generic helper methods

    /**
     * Get the high order bits from the mantissa.
     * Equivalent to adding and subtracting HEX_40000 but also works for very large numbers
     *
     * @param d the value to split
     * @return the high order part of the mantissa
     */
    private static double doubleHighPart(double d) {
        if (d > -Precision.SAFE_MIN && d < Precision.SAFE_MIN){
            return d; // These are un-normalised - don't try to convert
        }
        long xl = Double.doubleToRawLongBits(d); // can take raw bits because just gonna convert it back
        xl = xl & MASK_30BITS; // Drop low order bits
        return Double.longBitsToDouble(xl);
    }

    /** Compute the square root of a number.
     * <p><b>Note:</b> this implementation currently delegates to {@link Math#sqrt}
     * @param a number on which evaluation is done
     * @return square root of a
     */
    public static double sqrt(final double a) {
        return Math.sqrt(a);
    }

    /** Compute the hyperbolic cosine of a number.
     * @param x number on which evaluation is done
     * @return hyperbolic cosine of x
     */
    public static double cosh(double x) {
      if (Double.isNaN(x)) {
          return x;
      }

      // cosh[z] = (exp(z) + exp(-z))/2

      // For numbers with magnitude > 20, exp(-z) can be ignored in comparison with exp(z)
      if (x > 20) {
          if (x >= LOG_MAX_VALUE) { // Avoid overflow (MATH-905).
              final double t = exp(0.5 * x);
              return (0.5 * t) * t; // (1/2) * (exp(x/2))^2 = (1/2) * exp(x)
          } else {
              return 0.5 * exp(x);
          }
      } else if (x < -20) {
          if (x <= -LOG_MAX_VALUE) { // Avoid overflow (MATH-905).
              final double t = exp(-0.5 * x);
              return (0.5 * t) * t;
          } else {
              return 0.5 * exp(-x);
          }
      }

      // Calculate exp(abs(x)) with high precision
      final double[] expAbsXPrec = new double[2];
      exp(x < 0.0 ? -x : x, 0.0, expAbsXPrec);

      final double expAbsXHigh = expAbsXPrec[0];
      final double expAbsXLow = expAbsXPrec[1];

      // Compute 1 / exp(abs(x)) with high precision
      final double[] recipExpAbsXPrec = computeDoubleDoubleReciprocal(expAbsXHigh, expAbsXLow);
      final double recipExpAbsXHigh = recipExpAbsXPrec[0];
      final double recipExpAbsXLow = recipExpAbsXPrec[1];

      // Add exp(abs(x)) and 1/exp(abs(x)) in high precision
      final double[] sumExpRecipPrec = addDoubleDouble(expAbsXHigh, expAbsXLow, recipExpAbsXHigh, recipExpAbsXLow);

      return (sumExpRecipPrec[0] + sumExpRecipPrec[1]) * 0.5;
    }

    /** Compute the hyperbolic sine of a number.
     * @param x number on which evaluation is done
     * @return hyperbolic sine of x
     */
    public static double sinh(double x) {
      boolean negateResult = false;
      if (Double.isNaN(x)) {
          return x;
      }

      // sinh[z] = (exp(z) - exp(-z)) / 2

      // For values of z larger than about 20, exp(-z) can be ignored in comparison with exp(z)
      if (x > 20) {
          if (x >= LOG_MAX_VALUE) { // Avoid overflow (MATH-905).
              final double t = exp(0.5 * x);
              return (0.5 * t) * t;
          } else {
              return 0.5 * exp(x);
          }
      } else if (x < -20) {
          if (x <= -LOG_MAX_VALUE) { // Avoid overflow (MATH-905).
              final double t = exp(-0.5 * x);
              return (-0.5 * t) * t;
          } else {
              return -0.5 * exp(-x);
          }
      }

      if (x == 0) {
          return x; // Preserve sign of zero
      }

      double absX = x;
      if (x < 0.0) {
          absX = -x;
          negateResult = true;
      }

      double result;

      if (absX > 0.25) {
          // For larger values, use (exp(x) - 1/exp(x)) / 2
          final double[] expAbsXPrec = new double[2];
          exp(absX, 0.0, expAbsXPrec);

          final double expAbsXHigh = expAbsXPrec[0];
          final double expAbsXLow = expAbsXPrec[1];

          final double[] recipExpAbsXPrec = computeDoubleDoubleReciprocal(expAbsXHigh, expAbsXLow);
          final double recipExpAbsXHigh = recipExpAbsXPrec[0];
          final double recipExpAbsXLow = recipExpAbsXPrec[1];

          // Subtract 1/exp(abs(x)) from exp(abs(x)) in high precision
          final double[] diffExpRecipPrec = addDoubleDouble(expAbsXHigh, expAbsXLow, -recipExpAbsXHigh, -recipExpAbsXLow);

          result = (diffExpRecipPrec[0] + diffExpRecipPrec[1]) * 0.5;
      }
      else {
          // For smaller values, use expm1(x) and its relation to expm1(-x)
          // expm1(x) - expm1(-x) = expm1(x) - (-expm1(x) / (expm1(x) + 1))
          // = expm1(x) * (1 + 1 / (expm1(x) + 1)) = expm1(x) * ((expm1(x) + 2) / (expm1(x) + 1))
          // The original code calculates expm1(x) and -expm1(x) / (expm1(x) + 1)

          final double[] expm1AbsXPrec = new double[2];
          expm1(absX, expm1AbsXPrec);

          final double expm1AbsXHigh = expm1AbsXPrec[0];
          final double expm1AbsXLow = expm1AbsXPrec[1];

          // Compute expm1(-absX) = -expm1(absX) / (expm1(absX) + 1)
          final double denom = 1.0 + expm1AbsXHigh;
          final double denomLow = -(denom - 1.0 - expm1AbsXHigh) + expm1AbsXLow;
          
          final double[] negExpm1AbsXPrec = {-expm1AbsXHigh, -expm1AbsXLow}; // Numerator -expm1(x)

          // Division (negExpm1AbsXPrec) / (denom, denomLow)
          final double recipDenomEstimate = 1.0 / denom;
          double[] recipDenomSplit = splitDouble(recipDenomEstimate);
          double recipDenomHigh = recipDenomSplit[0];
          double recipDenomLow = recipDenomSplit[1];

          recipDenomLow += computeDivisionCorrection(1.0, 0.0, denom, denomLow, recipDenomHigh, recipDenomLow);

          // Multiply numerator (-expm1(absX)) by reciprocal of denominator
          double expm1NegAbsXHigh = negExpm1AbsXPrec[0] * recipDenomHigh; // High part of division result
          double expm1NegAbsXLow = negExpm1AbsXPrec[0] * recipDenomLow + negExpm1AbsXPrec[1] * recipDenomHigh + negExpm1AbsXPrec[1] * recipDenomLow; // Low part
          
          // Account for denominator's low part correction
          expm1NegAbsXLow += -denomLow * expm1AbsXHigh * recipDenomEstimate * recipDenomEstimate; // denomLow contribution to error

          // y = expm1(absX) + expm1(-absX)
          final double[] sumExpm1Prec = addDoubleDouble(expm1AbsXHigh, expm1AbsXLow, expm1NegAbsXHigh, expm1NegAbsXLow);

          result = (sumExpm1Prec[0] + sumExpm1Prec[1]) * 0.5;
      }

      return negateResult ? -result : result;
    }

    /** Compute the hyperbolic tangent of a number.
     * @param x number on which evaluation is done
     * @return hyperbolic tangent of x
     */
    public static double tanh(double x) {
      boolean negateResult = false;

      if (Double.isNaN(x)) {
          return x;
      }

      // tanh[z] = sinh[z] / cosh[z] = (exp(2x) - 1) / (exp(2x) + 1)

      // For magnitude > 20, sinh[z] == cosh[z] in double precision
      if (x > 20.0) {
          return 1.0;
      }

      if (x < -20) {
          return -1.0;
      }

      if (x == 0) {
          return x; // Preserve sign of zero
      }

      double absX = x;
      if (x < 0.0) {
          absX = -x;
          negateResult = true;
      }

      double result;
      if (absX >= 0.5) {
          // tanh(x) = (exp(2x) - 1) / (exp(2x) + 1)
          final double[] exp2XPrec = new double[2];
          exp(absX * 2.0, 0.0, exp2XPrec);

          final double exp2XHigh = exp2XPrec[0];
          final double exp2XLow = exp2XPrec[1];

          // Numerator: (exp(2x) - 1)
          final double[] numPrec = addDoubleDouble(exp2XHigh, exp2XLow, -1.0, 0.0);
          final double numHigh = numPrec[0];
          final double numLow = numPrec[1];

          // Denominator: (exp(2x) + 1)
          final double[] denPrec = addDoubleDouble(exp2XHigh, exp2XLow, 1.0, 0.0);
          final double denHigh = denPrec[0];
          final double denLow = denPrec[1];

          // ratio = num / den
          double ratioEstimate = numHigh / denHigh;
          double[] ratioSplit = splitDouble(ratioEstimate);
          double ratioHigh = ratioSplit[0];
          double ratioLow = ratioSplit[1];

          ratioLow += computeDivisionCorrection(numHigh, numLow, denHigh, denLow, ratioHigh, ratioLow);

          result = ratioHigh + ratioLow;
      }
      else {
          // tanh(x) = expm1(2x) / (expm1(2x) + 2)
          final double[] expm1_2XPrec = new double[2];
          expm1(absX * 2.0, expm1_2XPrec);

          final double expm1_2XHigh = expm1_2XPrec[0];
          final double expm1_2XLow = expm1_2XPrec[1];

          // Numerator: expm1(2x)
          final double numHigh = expm1_2XHigh;
          final double numLow = expm1_2XLow;

          // Denominator: (expm1(2x) + 2)
          final double[] denPrec = addDoubleDouble(expm1_2XHigh, expm1_2XLow, 2.0, 0.0);
          final double denHigh = denPrec[0];
          final double denLow = denPrec[1];

          // ratio = num / den
          double ratioEstimate = numHigh / denHigh;
          double[] ratioSplit = splitDouble(ratioEstimate);
          double ratioHigh = ratioSplit[0];
          double ratioLow = ratioSplit[1];

          ratioLow += computeDivisionCorrection(numHigh, numLow, denHigh, denLow, ratioHigh, ratioLow);

          result = ratioHigh + ratioLow;
      }

      return negateResult ? -result : result;
    }

    /** Compute the inverse hyperbolic cosine of a number.
     * @param a number on which evaluation is done
     * @return inverse hyperbolic cosine of a
     */
    public static double acosh(final double a) {
        // acosh(x) = log(x + sqrt(x^2 - 1))
        // No special handling for NaN or values < 1.0 as log will handle it by returning NaN.
        return FastMath.log(a + FastMath.sqrt(a * a - 1));
    }

    /** Compute the inverse hyperbolic sine of a number.
     * @param a number on which evaluation is done
     * @return inverse hyperbolic sine of a
     */
    public static double asinh(double a) {
        if (Double.isNaN(a)) {
            return Double.NaN;
        }

        boolean negative = (a < 0);
        final double absA = negative ? -a : a;

        double absAsinh;
        if (absA > 0.167) { // Roughly where polynomial approximation becomes less efficient
            // asinh(x) = log(x + sqrt(x^2 + 1))
            absAsinh = FastMath.log(FastMath.sqrt(absA * absA + 1) + absA);
        } else {
            // Use Taylor series expansion around 0 for small x: asinh(x) approx x - x^3/6 + 3x^5/40 - ...
            // The original code uses a nested polynomial (Horner-like form) for asinh(x)/x.
            final double a2 = absA * absA;
            absAsinh = absA * evaluateAsinhPolynomialTerm(a2, absA);
        }

        return negative ? -absAsinh : absAsinh;
    }

    /**
     * Helper method to evaluate the polynomial term for asinh(x)/x for small x.
     * The polynomial has the form 1 - a2 * (C1 - a2 * (C2 - ...)).
     * The coefficients C_i themselves are products (e.g., F_1_3, F_1_5 * F_3_4).
     *
     * @param a2 The square of the absolute input value (x*x).
     * @param absA The absolute input value (x).
     * @return The polynomial evaluation for asinh(x)/x, which then needs to be multiplied by absA.
     */
    private static double evaluateAsinhPolynomialTerm(final double a2, final double absA) {
        // The threshold for choosing polynomial degree implicitly comes from the original if-else if chain.
        // We will match the original structure by evaluating the polynomial based on the given value of a2.
        double polynomialTerm;

        if (absA > 0.097) { // Full series up to F_1_17
            polynomialTerm = F_1_17;
            polynomialTerm = F_1_15 - a2 * polynomialTerm * F_15_16;
            polynomialTerm = F_1_13 - a2 * polynomialTerm * F_13_14;
            polynomialTerm = F_1_11 - a2 * polynomialTerm * F_11_12;
            polynomialTerm = F_1_9 - a2 * polynomialTerm * F_9_10;
            polynomialTerm = F_1_7 - a2 * polynomialTerm * F_7_8;
            polynomialTerm = F_1_5 - a2 * polynomialTerm * F_5_6;
            polynomialTerm = F_1_3 - a2 * polynomialTerm * F_3_4;
            polynomialTerm = 1 - a2 * polynomialTerm * F_1_2;
        } else if (absA > 0.036) { // Series up to F_1_13
            polynomialTerm = F_1_13;
            polynomialTerm = F_1_11 - a2 * polynomialTerm * F_11_12;
            polynomialTerm = F_1_9 - a2 * polynomialTerm * F_9_10;
            polynomialTerm = F_1_7 - a2 * polynomialTerm * F_7_8;
            polynomialTerm = F_1_5 - a2 * polynomialTerm * F_5_6;
            polynomialTerm = F_1_3 - a2 * polynomialTerm * F_3_4;
            polynomialTerm = 1 - a2 * polynomialTerm * F_1_2;
        } else if (absA > 0.0036) { // Series up to F_1_9
            polynomialTerm = F_1_9;
            polynomialTerm = F_1_7 - a2 * polynomialTerm * F_7_8;
            polynomialTerm = F_1_5 - a2 * polynomialTerm * F_5_6;
            polynomialTerm = F_1_3 - a2 * polynomialTerm * F_3_4;
            polynomialTerm = 1 - a2 * polynomialTerm * F_1_2;
        } else { // Series up to F_1_5
            polynomialTerm = F_1_5;
            polynomialTerm = F_1_3 - a2 * polynomialTerm * F_3_4;
            polynomialTerm = 1 - a2 * polynomialTerm * F_1_2;
        }
        return polynomialTerm;
    }

    /** Compute the inverse hyperbolic tangent of a number.
     * @param a number on which evaluation is done
     * @return inverse hyperbolic tangent of a
     */
    public static double atanh(double a) {
        if (Double.isNaN(a)) {
            return Double.NaN;
        }

        boolean negative = (a < 0);
        final double absA = negative ? -a : a;

        double absAtanh;
        if (absA > 0.15) {
            // atanh(x) = 0.5 * log((1 + x) / (1 - x))
            absAtanh = 0.5 * FastMath.log((1 + absA) / (1 - absA));
        } else {
            // Use Taylor series expansion around 0 for small x: atanh(x) approx x + x^3/3 + x^5/5 + ...
            // The original code uses a nested polynomial (Horner-like form) for atanh(x)/x.
            final double a2 = absA * absA;
            absAtanh = absA * evaluateAtanhPolynomialTerm(a2, absA);
        }

        return negative ? -absAtanh : absAtanh;
    }

    /**
     * Helper method to evaluate the polynomial term for atanh(x)/x for small x.
     * The polynomial has the form 1 + a2 * (C1 + a2 * (C2 + ...)).
     *
     * @param a2 The square of the absolute input value (x*x).
     * @param absA The absolute input value (x).
     * @return The polynomial evaluation for atanh(x)/x, which then needs to be multiplied by absA.
     */
    private static double evaluateAtanhPolynomialTerm(final double a2, final double absA) {
        double polynomialTerm;

        if (absA > 0.087) { // Full series up to F_1_17
            polynomialTerm = F_1_17;
            polynomialTerm = F_1_15 + a2 * polynomialTerm;
            polynomialTerm = F_1_13 + a2 * polynomialTerm;
            polynomialTerm = F_1_11 + a2 * polynomialTerm;
            polynomialTerm = F_1_9 + a2 * polynomialTerm;
            polynomialTerm = F_1_7 + a2 * polynomialTerm;
            polynomialTerm = F_1_5 + a2 * polynomialTerm;
            polynomialTerm = F_1_3 + a2 * polynomialTerm;
            polynomialTerm = 1 + a2 * polynomialTerm;
        } else if (absA > 0.031) { // Series up to F_1_13
            polynomialTerm = F_1_13;
            polynomialTerm = F_1_11 + a2 * polynomialTerm;
            polynomialTerm = F_1_9 + a2 * polynomialTerm;
            polynomialTerm = F_1_7 + a2 * polynomialTerm;
            polynomialTerm = F_1_5 + a2 * polynomialTerm;
            polynomialTerm = F_1_3 + a2 * polynomialTerm;
            polynomialTerm = 1 + a2 * polynomialTerm;
        } else if (absA > 0.003) { // Series up to F_1_9
            polynomialTerm = F_1_9;
            polynomialTerm = F_1_7 + a2 * polynomialTerm;
            polynomialTerm = F_1_5 + a2 * polynomialTerm;
            polynomialTerm = F_1_3 + a2 * polynomialTerm;
            polynomialTerm = 1 + a2 * polynomialTerm;
        } else { // Series up to F_1_5
            polynomialTerm = F_1_5;
            polynomialTerm = F_1_3 + a2 * polynomialTerm;
            polynomialTerm = 1 + a2 * polynomialTerm;
        }
        return polynomialTerm;
    }

    /** Compute the signum of a number.
     * The signum is -1 for negative numbers, +1 for positive numbers and 0 otherwise
     * @param a number on which evaluation is done
     * @return -1.0, -0.0, +0.0, +1.0 or NaN depending on sign of a
     */
    public static double signum(final double a) {
        return (a < 0.0) ? -1.0 : ((a > 0.0) ? 1.0 : a); // return +0.0/-0.0/NaN depending on a
    }

    /** Compute the signum of a number.
     * The signum is -1 for negative numbers, +1 for positive numbers and 0 otherwise
     * @param a number on which evaluation is done
     * @return -1.0, -0.0, +0.0, +1.0 or NaN depending on sign of a
     */
    public static float signum(final float a) {
        return (a < 0.0f) ? -1.0f : ((a > 0.0f) ? 1.0f : a); // return +0.0/-0.0/NaN depending on a
    }

    /** Compute next number towards positive infinity.
     * @param a number to which neighbor should be computed
     * @return neighbor of a towards positive infinity
     */
    public static double nextUp(final double a) {
        return nextAfter(a, Double.POSITIVE_INFINITY);
    }

    /** Compute next number towards positive infinity.
     * @param a number to which neighbor should be computed
     * @return neighbor of a towards positive infinity
     */
    public static float nextUp(final float a) {
        return nextAfter(a, Float.POSITIVE_INFINITY);
    }

    /** Returns a pseudo-random number between 0.0 and 1.0.
     * <p><b>Note:</b> this implementation currently delegates to {@link Math#random}
     * @return a random number between 0.0 and 1.0
     */
    public static double random() {
        return Math.random();
    }

    /**
     * Exponential function.
     *
     * Computes exp(x), function result is nearly rounded.   It will be correctly
     * rounded to the theoretical value for 99.9% of input values, otherwise it will
     * have a 1 UPL error.
     *
     * Method:
     *    Lookup intVal = exp(int(x))
     *    Lookup fracVal = exp(int(x-int(x) / 1024.0) * 1024.0 );
     *    Compute z as the exponential of the remaining bits by a polynomial minus one
     *    exp(x) = intVal * fracVal * (1 + z)
     *
     * Accuracy:
     *    Calculation is done with 63 bits of precision, so result should be correctly
     *    rounded for 99.9% of input values, with less than 1 ULP error otherwise.
     *
     * @param x   a double
     * @return double e<sup>x</sup>
     */
    public static double exp(double x) {
        return exp(x, 0.0, null);
    }

    /**
     * Internal helper method for exponential function.
     * @param x original argument of the exponential function
     * @param extra extra bits of precision on input (To Be Confirmed)
     * @param hiPrec extra bits of precision on output (To Be Confirmed)
     * @return exp(x)
     */
    private static double exp(double x, double extra, double[] hiPrec) {
        double integerPartA;
        double integerPartB;
        int integerValue;

        // Lookup exp(floor(x)).
        // integerPartA will have the upper 22 bits, integerPartB will have the lower 52 bits.
        if (x < 0.0) {
            integerValue = (int) -x;

            if (integerValue > 746) {
                if (hiPrec != null) {
                    hiPrec[0] = 0.0;
                    hiPrec[1] = 0.0;
                }
                return 0.0;
            }

            if (integerValue > 709) {
                // This will produce a subnormal output
                final double result = exp(x + 40.19140625, extra, hiPrec) / 285040095144011776.0;
                if (hiPrec != null) {
                    hiPrec[0] /= 285040095144011776.0;
                    hiPrec[1] /= 285040095144011776.0;
                }
                return result;
            }

            if (integerValue == 709) {
                // exp(1.494140625) is nearly a machine number...
                final double result = exp(x + 1.494140625, extra, hiPrec) / 4.455505956692756620;
                if (hiPrec != null) {
                    hiPrec[0] /= 4.455505956692756620;
                    hiPrec[1] /= 4.455505956692756620;
                }
                return result;
            }

            integerValue++;

            integerPartA = ExpIntTable.EXP_INT_TABLE_A[EXP_INT_TABLE_MAX_INDEX - integerValue];
            integerPartB = ExpIntTable.EXP_INT_TABLE_B[EXP_INT_TABLE_MAX_INDEX - integerValue];

            integerValue = -integerValue;
        } else {
            integerValue = (int) x;

            if (integerValue > 709) {
                if (hiPrec != null) {
                    hiPrec[0] = Double.POSITIVE_INFINITY;
                    hiPrec[1] = 0.0;
                }
                return Double.POSITIVE_INFINITY;
            }

            integerPartA = ExpIntTable.EXP_INT_TABLE_A[EXP_INT_TABLE_MAX_INDEX + integerValue];
            integerPartB = ExpIntTable.EXP_INT_TABLE_B[EXP_INT_TABLE_MAX_INDEX + integerValue];
        }

        // Get the fractional part of x, find the greatest multiple of 2^-10 less than
        // x and look up the exp function of it.
        // fractionalPartA will have the upper 22 bits, fractionalPartB the lower 52 bits.
        final int fractionalIndex = (int) ((x - integerValue) * 1024.0);
        final double fractionalPartA = ExpFracTable.EXP_FRAC_TABLE_A[fractionalIndex];
        final double fractionalPartB = ExpFracTable.EXP_FRAC_TABLE_B[fractionalIndex];

        // epsilon is the difference in x from the nearest multiple of 2^-10.  It
        // has a value in the range 0 <= epsilon < 2^-10.
        // Do the subtraction from x as the last step to avoid possible loss of percison.
        final double epsilon = x - (integerValue + fractionalIndex / 1024.0);

        // Compute z = exp(epsilon) - 1.0 via a minimax polynomial.  z has
        // full double precision (52 bits).  Since z < 2^-10, we will have
        // 62 bits of precision when combined with the contant 1.  This will be
        // used in the last addition below to get proper rounding.

        // Remez generated polynomial.  Converges on the interval [0, 2^-10], error
        // is less than 0.5 ULP
        double z = 0.04168701738764507;
        z = z * epsilon + 0.1666666505023083;
        z = z * epsilon + 0.5000000000042687;
        z = z * epsilon + 1.0;
        z = z * epsilon + -3.940510424527919E-20;

        // Compute (integerPartA+integerPartB) * (fractionalPartA+fractionalPartB) by binomial
        // expansion.
        // tempHigh is exact since integerPartA and integerPartB only have 22 bits each.
        // tempLow will have 52 bits of precision.
        double tempHigh = integerPartA * fractionalPartA;
        double tempLow = integerPartA * fractionalPartB + integerPartB * fractionalPartA + integerPartB * fractionalPartB;

        // Compute the result.  (1+z)(tempHigh+tempLow).  Order of operations is
        // important.  For accuracy add by increasing size.  tempHigh is exact and
        // much larger than the others.  If there are extra bits specified from the
        // pow() function, use them.
        final double combinedBase = tempLow + tempHigh;
        final double result;
        if (extra != 0.0) {
            result = combinedBase * extra * z + combinedBase * extra + combinedBase * z + tempLow + tempHigh;
        } else {
            result = combinedBase * z + tempLow + tempHigh;
        }

        if (hiPrec != null) {
            // If requesting high precision
            hiPrec[0] = tempHigh;
            hiPrec[1] = combinedBase * extra * z + combinedBase * extra + combinedBase * z + tempLow;
        }

        return result;
    }

    /** Compute exp(x) - 1
     * @param x number to compute shifted exponential
     * @return exp(x) - 1
     */
    public static double expm1(double x) {
      return expm1(x, null);
    }

    /** Internal helper method for expm1
     * @param x number to compute shifted exponential
     * @param hiPrecOut receive high precision result for -1.0 < x < 1.0
     * @return exp(x) - 1
     */
    private static double expm1(double x, double hiPrecOut[]) {
        if (Double.isNaN(x) || x == 0.0) { // NaN or zero
            return x;
        }

        if (x <= -1.0 || x >= 1.0) {
            // If not between +/- 1.0, use exp(x) - 1.0
            double[] expXPrec = new double[2];
            exp(x, 0.0, expXPrec);
            if (x > 0.0) {
                return -1.0 + expXPrec[0] + expXPrec[1];
            } else {
                // Compute (-1.0 + expXPrec[0]) + expXPrec[1]
                double ra = -1.0 + expXPrec[0];
                double rb = -(ra + 1.0 - expXPrec[0]);
                rb += expXPrec[1];
                return ra + rb;
            }
        }

        double baseHigh;
        double baseLow;
        double epsilon;
        boolean negateInput = false;

        if (x < 0.0) {
            x = -x;
            negateInput = true;
        }

        {
            final int fractionalIndex = (int) (x * 1024.0);
            double tempA = ExpFracTable.EXP_FRAC_TABLE_A[fractionalIndex] - 1.0;
            double tempB = ExpFracTable.EXP_FRAC_TABLE_B[fractionalIndex];

            double tempSum = tempA + tempB;
            tempB = -(tempSum - tempA - tempB); // Error term for tempA + tempB
            tempA = tempSum; // Renormalized sum

            double[] splitTempA = splitDouble(tempA);
            baseHigh = splitTempA[0];
            baseLow = tempB + splitTempA[1]; // Add low part of tempA with error term tempB

            epsilon = x - fractionalIndex / 1024.0;
        }

        // Compute expm1(epsilon) using a polynomial. (z is the error term for epsilon).
        double zError = 0.008336750013465571;
        zError = zError * epsilon + 0.041666663879186654;
        zError = zError * epsilon + 0.16666666666745392;
        zError = zError * epsilon + 0.49999999999999994;
        zError = zError * epsilon;
        zError = zError * epsilon;

        double zHigh = epsilon;
        double tempSum = zHigh + zError;
        zError = -(tempSum - zHigh - zError); // Error term for zHigh + zError
        zHigh = tempSum; // Renormalized sum

        double[] splitZHigh = splitDouble(zHigh);
        zError += splitZHigh[1];
        zHigh = splitZHigh[0];

        // Combine the parts. expm1(a+b) = expm1(a) + expm1(b) + expm1(a)*expm1(b)
        // Calculation involves (zHigh+zError) * (baseHigh+baseLow)

        // Term 1: zHigh * baseHigh
        double term1High = zHigh * baseHigh;
        double term1Low = zHigh * baseLow + zError * baseHigh + zError * baseLow;

        // Now add all the parts: (term1High+term1Low) + (zHigh+zError) + (baseHigh+baseLow)
        double resultHigh = term1High;
        double resultLow = term1Low;

        double[] sum1 = addDoubleDouble(resultHigh, resultLow, zHigh, zError); // result = term1 + z
        resultHigh = sum1[0];
        resultLow = sum1[1];

        double[] sum2 = addDoubleDouble(resultHigh, resultLow, baseHigh, baseLow); // result = (term1 + z) + base
        resultHigh = sum2[0];
        resultLow = sum2[1];

        if (negateInput) {
            // Compute expm1(-x) = -expm1(x) / (expm1(x) + 1)
            final double denomHigh = 1.0 + resultHigh;
            final double denomLow = -(denomHigh - 1.0 - resultHigh) + resultLow;

            // Reciprocal of denominator
            final double recipDenomEstimate = 1.0 / denomHigh;
            double[] recipDenomSplit = splitDouble(recipDenomEstimate);
            double recipDenomHigh = recipDenomSplit[0];
            double recipDenomLow = recipDenomSplit[1];

            recipDenomLow += computeDivisionCorrection(1.0, 0.0, denomHigh, denomLow, recipDenomHigh, recipDenomLow);

            // Numerator is -expm1(x) = -(resultHigh + resultLow)
            final double negResultHigh = -resultHigh;
            final double negResultLow = -resultLow;

            // Multiply numerator by reciprocal of denominator
            double finalRatioHigh = negResultHigh * recipDenomHigh;
            double finalRatioLow = negResultHigh * recipDenomLow + negResultLow * recipDenomHigh + negResultLow * recipDenomLow;

            // Account for denominator's low part correction
            finalRatioLow += -denomLow * negResultHigh * recipDenomEstimate * recipDenomEstimate;

            resultHigh = finalRatioHigh;
            resultLow = finalRatioLow;
        }

        if (hiPrecOut != null) {
            hiPrecOut[0] = resultHigh;
            hiPrecOut[1] = resultLow;
        }

        return resultHigh + resultLow;
    }

    /**
     * Natural logarithm.
     *
     * @param x   a double
     * @return log(x)
     */
    public static double log(final double x) {
        return log(x, null);
    }

    /**
     * Internal helper method for natural logarithm function.
     * @param x original argument of the natural logarithm function
     * @param hiPrec extra bits of precision on output (To Be Confirmed)
     * @return log(x)
     */
    private static double log(final double x, final double[] hiPrec) {
        if (x==0) { // Handle special case of +0/-0
            return Double.NEGATIVE_INFINITY;
        }
        long bits = Double.doubleToRawLongBits(x);

        // Handle special cases of negative input, and NaN
        if (((bits & 0x8000000000000000L) != 0 || Double.isNaN(x)) && x != 0.0) {
            if (hiPrec != null) {
                hiPrec[0] = Double.NaN;
                hiPrec[1] = 0.0; // Ensure error part is also NaN or 0
            }
            return Double.NaN;
        }

        // Handle special cases of Positive infinity.
        if (x == Double.POSITIVE_INFINITY) {
            if (hiPrec != null) {
                hiPrec[0] = Double.POSITIVE_INFINITY;
                hiPrec[1] = 0.0;
            }
            return Double.POSITIVE_INFINITY;
        }

        // Extract the exponent
        int exponent = (int)(bits >> 52)-1023;

        if ((bits & 0x7ff0000000000000L) == 0) {
            // Subnormal!
            if (x == 0) { // Already handled, but double check for clarity
                if (hiPrec != null) {
                    hiPrec[0] = Double.NEGATIVE_INFINITY;
                    hiPrec[1] = 0.0;
                }
                return Double.NEGATIVE_INFINITY;
            }

            // Normalize the subnormal number.
            bits <<= 1;
            while ( (bits & 0x0010000000000000L) == 0) {
                --exponent;
                bits <<= 1;
            }
        }

        if ((exponent == -1 || exponent == 0) && x < 1.01 && x > 0.99 && hiPrec == null) {
            // The normal method doesn't work well in the range [0.99, 1.01],
            // so do a straight polynomial expansion in higher precision.

            // Compute x - 1.0 and split it
            final double xMinus1 = x - 1.0;
            final double[] xMinus1Split = splitDouble(xMinus1);
            double xTermHigh = xMinus1Split[0];
            double xTermLow = xMinus1Split[1];

            final double[] lnCoef_last = LN_QUICK_COEF[LN_QUICK_COEF.length - 1];
            double polyValHigh = lnCoef_last[0];
            double polyValLow = lnCoef_last[1];

            for (int i = LN_QUICK_COEF.length - 2; i >= 0; i--) {
                // Multiply polyVal * xTerm
                double[] prod = multiplyDoubleDouble(polyValHigh, polyValLow, xTermHigh, xTermLow);
                polyValHigh = prod[0];
                polyValLow = prod[1];

                // Add lnQuickCoef
                final double[] lnCoef_i = LN_QUICK_COEF[i];
                double[] sum = addDoubleDouble(polyValHigh, polyValLow, lnCoef_i[0], lnCoef_i[1]);
                polyValHigh = sum[0];
                polyValLow = sum[1];
            }

            // Final multiplication polyVal * xTerm
            double[] finalProd = multiplyDoubleDouble(polyValHigh, polyValLow, xTermHigh, xTermLow);
            polyValHigh = finalProd[0];
            polyValLow = finalProd[1];

            return polyValHigh + polyValLow;
        }

        // lnm is a log of a number in the range of 1.0 - 2.0, so 0 <= lnm < ln(2)
        final double[] lnMantissaValue = lnMant.LN_MANT[(int)((bits & 0x000ffc0000000000L) >> 42)];

        // epsilon = (x - mantissa_high_part) / mantissa_high_part
        // The original calculation is `(bits & 0x3ffffffffffL) / (TWO_POWER_52 + (bits & 0x000ffc0000000000L))`
        // This is effectively `(x - floor(x)) / floor(x)` for the mantissa range
        final double epsilon = (bits & 0x3ffffffffffL) / (TWO_POWER_52 + (bits & 0x000ffc0000000000L));

        double lnZHigh = 0.0;
        double lnZLow = 0.0;

        if (hiPrec != null) {
            // Split epsilon -> x_h, x_l
            double[] epsilonSplit = splitDouble(epsilon);
            double epsilonHigh = epsilonSplit[0];
            double epsilonLow = epsilonSplit[1];

            // Refine epsilon with higher precision division
            final double numerator = bits & 0x3ffffffffffL;
            final double denominator = TWO_POWER_52 + (bits & 0x000ffc0000000000L);
            epsilonLow += computeDivisionCorrection(numerator, 0.0, denominator, 0.0, epsilonHigh, epsilonLow);

            // Remez polynomial evaluation
            final double[] lnCoef_last = LN_HI_PREC_COEF[LN_HI_PREC_COEF.length-1];
            double polyValHigh = lnCoef_last[0];
            double polyValLow = lnCoef_last[1];

            for (int i = LN_HI_PREC_COEF.length - 2; i >= 0; i--) {
                // Multiply polyVal * epsilon
                double[] prod = multiplyDoubleDouble(polyValHigh, polyValLow, epsilonHigh, epsilonLow);
                polyValHigh = prod[0];
                polyValLow = prod[1];

                // Add lnHiPrecCoef
                final double[] lnCoef_i = LN_HI_PREC_COEF[i];
                double[] sum = addDoubleDouble(polyValHigh, polyValLow, lnCoef_i[0], lnCoef_i[1]);
                polyValHigh = sum[0];
                polyValLow = sum[1];
            }

            // Final multiplication polyVal * epsilon
            double[] finalProd = multiplyDoubleDouble(polyValHigh, polyValLow, epsilonHigh, epsilonLow);
            lnZHigh = finalProd[0];
            lnZLow = finalProd[1];

            // The original logic `lnza = aa + ab; lnzb = -(lnza - aa - ab);` effectively renormalizes.
            // Our addDoubleDouble does this already.
            // We can re-use addDoubleDouble here to combine finalProd[0] and finalProd[1] with renormalization
            double[] combinedLnZ = addDoubleDouble(lnZHigh, lnZLow, 0.0, 0.0); // Renormalize
            lnZHigh = combinedLnZ[0];
            lnZLow = combinedLnZ[1];

        } else {
            // High precision not required. Eval Remez polynomial using standard double precision
            lnZHigh = -0.16624882440418567;
            lnZHigh = lnZHigh * epsilon + 0.19999954120254515;
            lnZHigh = lnZHigh * epsilon + -0.2499999997677497;
            lnZHigh = lnZHigh * epsilon + 0.3333333333332802;
            lnZHigh = lnZHigh * epsilon + -0.5;
            lnZHigh = lnZHigh * epsilon + 1.0;
            lnZHigh = lnZHigh * epsilon;
        }

        // Sum all the parts for final result: LN_2_A*exp + lnMantissaValue[0] + lnZHigh + LN_2_B*exp + lnMantissaValue[1] + lnZLow
        double resultHigh = LN_2_A * exponent;
        double resultLow = 0.0;

        double[] sum1 = addDoubleDouble(resultHigh, resultLow, lnMantissaValue[0], 0.0);
        resultHigh = sum1[0]; resultLow = sum1[1];

        double[] sum2 = addDoubleDouble(resultHigh, resultLow, lnZHigh, lnZLow);
        resultHigh = sum2[0]; resultLow = sum2[1];

        double[] sum3 = addDoubleDouble(resultHigh, resultLow, LN_2_B * exponent, 0.0);
        resultHigh = sum3[0]; resultLow = sum3[1];

        double[] sum4 = addDoubleDouble(resultHigh, resultLow, lnMantissaValue[1], 0.0);
        resultHigh = sum4[0]; resultLow = sum4[1];

        if (hiPrec != null) {
            hiPrec[0] = resultHigh;
            hiPrec[1] = resultLow;
        }

        return resultHigh + resultLow;
    }

    /**
     * Computes log(1 + x).
     *
     * @param x Number.
     * @return {@code log(1 + x)}.
     */
    public static double log1p(final double x) {
        if (x == -1) {
            return Double.NEGATIVE_INFINITY;
        }

        if (Double.isInfinite(x)) {
            return Double.POSITIVE_INFINITY;
        }

        if (x > 1e-6 || x < -1e-6) {
            // For larger |x|, compute log(1+x) using log(y) + (x - (y-1))/y correction
            final double onePlusX = 1 + x;
            final double xPlus1Error = -(onePlusX - 1 - x);

            final double[] hiPrec = new double[2];
            final double logResult = log(onePlusX, hiPrec);
            if (Double.isInfinite(logResult)) { // Don't allow this to be converted to NaN
                return logResult;
            }

            // Do a Taylor series expansion around onePlusX:
            // log(onePlusX + xPlus1Error) = log(onePlusX) + xPlus1Error / onePlusX - (xPlus1Error / onePlusX)^2 / 2 + ...
            // The original uses: f(x+y) = f(x) + f'(x)y + f''(x)/2 y^2
            // where f(x) = log(x), f'(x)=1/x, f''(x)=-1/x^2
            final double firstOrderCorrection = xPlus1Error / onePlusX;
            final double secondOrderCorrection = 0.5 * firstOrderCorrection;
            final double epsilonCorrection = (secondOrderCorrection + 1) * firstOrderCorrection;
            return epsilonCorrection + hiPrec[1] + hiPrec[0];
        } else {
            // Value is small |x| < 1e-6, do a Taylor series centered on 1.
            // log(1+x) = x - x^2/2 + x^3/3 - x^4/4 + ...
            // The original uses a truncated series: x * (1 - x/2 + x^2/3)
            final double xSquared = x * x;
            final double y = (x * F_1_3 - F_1_2) * x + 1; // 1 - x/2 + x^2/3
            return y * x;
        }
    }

    /** Compute the base 10 logarithm.
     * @param x a number
     * @return log10(x)
     */
    public static double log10(final double x) {
        final double hiPrec[] = new double[2];

        final double logResult = log(x, hiPrec);
        if (Double.isInfinite(logResult)){ // don't allow this to be converted to NaN
            return logResult;
        }

        final double logXHigh = hiPrec[0];
        final double logXLow = hiPrec[1];

        final double[] logXSplit = splitDouble(logXHigh);
        final double lna = logXSplit[0];
        final double lnb = logXSplit[1] + logXLow;

        final double rln10a = 0.4342944622039795;
        final double rln10b = 1.9699272335463627E-8;

        // Result = (rln10a + rln10b) * (lna + lnb)
        // = rln10a*lna + rln10a*lnb + rln10b*lna + rln10b*lnb
        return rln10b * lnb + rln10b * lna + rln10a * lnb + rln10a * lna;
    }

    /**
     * Computes the <a href="http://mathworld.wolfram.com/Logarithm.html">
     * logarithm</a> in a given base.
     *
     * Returns {@code NaN} if either argument is negative.
     * If {@code base} is 0 and {@code x} is positive, 0 is returned.
     * If {@code base} is positive and {@code x} is 0,
     * {@code Double.NEGATIVE_INFINITY} is returned.
     * If both arguments are 0, the result is {@code NaN}.
     *
     * @param base Base of the logarithm, must be greater than 0.
     * @param x Argument, must be greater than 0.
     * @return the value of the logarithm, i.e. the number {@code y} such that
     * <code>base<sup>y</sup> = x</code>.
     * @since 1.2 (previously in {@code MathUtils}, moved as of version 3.0)
     */
    public static double log(double base, double x) {
        return log(x) / log(base);
    }

    /**
     * Power function.  Compute x^y.
     *
     * @param x   a double
     * @param y   a double
     * @return double
     */
    public static double pow(double x, double y) {
        final double logXPrec[] = new double[2];

        if (y == 0.0) {
            return 1.0;
        }

        if (Double.isNaN(x)) { // X is NaN
            return x;
        }

        if (x == 0) {
            long xBits = Double.doubleToRawLongBits(x);
            if ((xBits & 0x8000000000000000L) != 0) { // -zero
                long yInt = (long) y;
                if (y < 0 && y == yInt && (yInt & 1) == 1) {
                    return Double.NEGATIVE_INFINITY;
                }
                if (y > 0 && y == yInt && (yInt & 1) == 1) {
                    return -0.0;
                }
            }

            if (y < 0) {
                return Double.POSITIVE_INFINITY;
            }
            if (y > 0) {
                return 0.0;
            }
            return Double.NaN; // 0^0 is NaN
        }

        if (x == Double.POSITIVE_INFINITY) {
            if (Double.isNaN(y)) { // y is NaN
                return y;
            }
            if (y < 0.0) {
                return 0.0;
            } else {
                return Double.POSITIVE_INFINITY;
            }
        }

        if (y == Double.POSITIVE_INFINITY) {
            if (x * x == 1.0) {
                return Double.NaN; // 1^Inf is NaN
            }
            if (x * x > 1.0) {
                return Double.POSITIVE_INFINITY;
            } else {
                return 0.0;
            }
        }

        if (x == Double.NEGATIVE_INFINITY) {
            if (Double.isNaN(y)) { // y is NaN
                return y;
            }

            if (y < 0) {
                long yInt = (long) y;
                if (y == yInt && (yInt & 1) == 1) {
                    return -0.0;
                }
                return 0.0;
            }

            if (y > 0)  {
                long yInt = (long) y;
                if (y == yInt && (yInt & 1) == 1) {
                    return Double.NEGATIVE_INFINITY;
                }
                return Double.POSITIVE_INFINITY;
            }
        }

        if (y == Double.NEGATIVE_INFINITY) {

            if (x * x == 1.0) {
                return Double.NaN; // 1^(-Inf) is NaN
            }

            if (x * x < 1.0) {
                return Double.POSITIVE_INFINITY;
            } else {
                return 0.0;
            }
        }

        // Handle special case x<0
        if (x < 0) {
            // If y is an integer, (-x)^y or -((-x)^y)
            // If y is not an integer, result is NaN
            if (y >= TWO_POWER_53 || y <= -TWO_POWER_53) { // y is too large to represent precisely as an integer
                // Treat as non-integer, or rely on pow(-x,y) to handle it if y is not exactly an integer
                // Original behavior implies this leads to NaN, or (-x)^y if y is even
                // For very large y, the sign will effectively alternate, but double precision can't track that.
                // The original code uses: return pow(-x, y); which implies it assumes y is even here.
                // It's a heuristic based on properties of floating point powers.
                // Let's preserve the original behavior.
                return pow(-x, y);
            }

            if (y == (long) y) {
                // If y is an integer, return (-1)^y * pow(abs(x), y)
                return (((long)y & 1) == 0) ? pow(-x, y) : -pow(-x, y);
            } else {
                return Double.NaN;
            }
        }

        // x > 0.  Compute exp(y * log(x))

        // Split y into yHigh and yLow such that y = yHigh+yLow
        final double yHigh;
        final double yLow;
        if (y < 8e298 && y > -8e298) { // Heuristic range for simpler splitting
            double temp = y * HEX_40000000;
            yHigh = y + temp - temp;
            yLow = y - yHigh;
        } else {
            // For very large y, use a different splitting factor or a recursive split
            // Original code's splitting for large Y: It looks like it tries to extract more bits
            // tmp1 = y * 2^-10; tmp2 = tmp1 * 2^-10;
            // ya = (tmp1+tmp2-tmp1) * (2^10 * 2^10) -- seems like trying to force 2^30 aligned bits.
            double temp1 = y * 9.31322574615478515625E-10; // 2^-30
            double temp2 = temp1 * 9.31322574615478515625E-10; // 2^-60
            yHigh = (temp1 + temp2 - temp1) * TWO_POWER_52 * TWO_POWER_52; // (effectively y rounded to 2^60 factor) * 2^52 * 2^52 is odd
            yLow = y - yHigh;
        }

        // Compute log(x) in high precision
        final double logResult = log(x, logXPrec);
        if (Double.isInfinite(logResult)){ // don't allow this to be converted to NaN
            return logResult;
        }

        double logXHigh = logXPrec[0];
        double logXLow = logXPrec[1];

        // Resplit logXHigh to get a clean split if not already perfect
        double[] logXSplit = splitDouble(logXHigh);
        logXLow += logXHigh - logXSplit[0]; // Add the error from split to low part
        logXHigh = logXSplit[0];

        // Compute y * log(x) = (yHigh+yLow) * (logXHigh+logXLow)
        final double productHigh = logXHigh * yHigh;
        final double productLow = logXHigh * yLow + logXLow * yHigh + logXLow * yLow;

        // Add productLow to productHigh with renormalization
        double exponentHigh = productHigh + productLow;
        double exponentLow = -(exponentHigh - productHigh - productLow);

        // Compute exp(exponentHigh + exponentLow)
        // Use polynomial for exp(exponentLow) - 1.0 (z is this term)
        double z = 1.0 / 120.0;
        z = z * exponentLow + (1.0 / 24.0);
        z = z * exponentLow + (1.0 / 6.0);
        z = z * exponentLow + 0.5;
        z = z * exponentLow + 1.0;
        z = z * exponentLow; // z = exp(exponentLow) - 1

        final double finalResult = exp(exponentHigh, z, null);
        // The `result = result + result * z;` in original code is effectively `exp(expH) * (1 + (expL-1))` which is `exp(expH)*(expL)`
        // The call to `exp(exponentHigh, z, null)` already factors in `z` as `extra` parameter `exp(a, extra, hiPrec)`
        // `result = tempC*z + tempB + tempA;` from exp
        return finalResult;
    }


    /**
     * Raise a double to an int power.
     *
     * @param d Number to raise.
     * @param e Exponent.
     * @return d<sup>e</sup>
     * @since 3.1
     */
    public static double pow(double d, int e) {

        if (e == 0) {
            return 1.0;
        } else if (e < 0) {
            e = -e;
            d = 1.0 / d;
        }

        // Split d as two 26 bits numbers for Veltkamp TwoProduct algorithm
        // beware the following expressions must NOT be simplified, they rely on floating point arithmetic properties
        final int splitFactor = 0x8000001; // Effectively 2^27 + 1 (used to split 53-bit mantissa roughly in half)
        final double cd       = splitFactor * d;
        final double d1High   = cd - (cd - d);
        final double d1Low    = d - d1High;

        // Prepare result in double-double format
        double resultHigh = 1.0;
        double resultLow  = 0.0;

        // d^(2p) in double-double format
        double d2p     = d; // Full precision current power
        double d2pHigh = d1High; // High part of current power
        double d2pLow  = d1Low;  // Low part of current power

        while (e != 0) {

            if ((e & 0x1) != 0) { // If exponent bit is set, multiply result by d^(2p)
                // Accurate multiplication result = result * d^(2p) using Veltkamp TwoProduct algorithm
                // (r_h + r_l) * (dp_h + dp_l)
                // beware the following expressions must NOT be simplified, they rely on floating point arithmetic properties
                final double productHighPart = resultHigh * d2p; // First approximation

                // Split resultHigh for Veltkamp
                final double cRH     = splitFactor * resultHigh;
                final double rHH     = cRH - (cRH - resultHigh);
                final double rHL     = resultHigh - rHH;

                // Calculate low part of product using error terms
                final double productLowPart = rHL * d2pLow - (((productHighPart - rHH * d2pHigh) - rHL * d2pHigh) - rHH * d2pLow);
                
                resultLow = resultLow * d2p + productLowPart + (resultHigh * d2p - productHighPart); // Accumulate error
                resultHigh = productHighPart;
            }

            // Accurate squaring d^(2(p+1)) = d^(2p) * d^(2p) using Veltkamp TwoProduct algorithm
            // (dp_h + dp_l) * (dp_h + dp_l)
            // beware the following expressions must NOT be simplified, they rely on floating point arithmetic properties
            final double squareHighPart = d2pHigh * d2p; // First approximation

            // Split d2pHigh for Veltkamp
            final double cD2pH   = splitFactor * d2pHigh;
            final double d2pHH   = cD2pH - (cD2pH - d2pHigh);
            final double d2pHL   = d2pHigh - d2pHH;

            // Calculate low part of square using error terms
            final double squareLowPart = d2pHL * d2pLow - (((squareHighPart - d2pHH * d2pHigh) - d2pHL * d2pHigh) - d2pHH * d2pLow);
            
            d2pLow  = d2pLow * d2p + squareLowPart + (d2pHigh * d2p - squareHighPart); // Accumulate error
            d2pHigh = squareHighPart;
            d2p     = d2pHigh + d2pLow; // Update full precision value for next iteration

            e = e >> 1; // Move to next bit of exponent
        }

        return resultHigh + resultLow;
    }

    /**
     *  Computes sin(x) - x, where |x| < 1/16.
     *  Use a Remez polynomial approximation.
     *  @param x a number smaller than 1/16
     *  @return sin(x) - x
     */
    private static double polySine(final double x)
    {
        final double x2 = x*x;

        double p = 2.7553817452272217E-6;
        p = p * x2 + -1.9841269659586505E-4;
        p = p * x2 + 0.008333333333329196;
        p = p * x2 + -0.16666666666666666;
        p = p * x2 * x; // Multiply by x^3 (x2 * x)

        return p;
    }

    /**
     *  Computes cos(x) - 1, where |x| < 1/16.
     *  Use a Remez polynomial approximation.
     *  @param x a number smaller than 1/16
     *  @return cos(x) - 1
     */
    private static double polyCosine(final double x) {
        final double x2 = x*x;

        double p = 2.479773539153719E-5;
        p = p * x2 + -0.0013888888689039883;
        p = p * x2 + 0.041666666666621166;
        p = p * x2 + -0.49999999999999994;
        p *= x2; // Multiply by x^2

        return p;
    }

    /**
     *  Compute sine over the first quadrant (0 < x < pi/2).
     *  Uses combination of table lookup and rational polynomial expansion.
     *  @param angleHigh high bits of the angle (xa)
     *  @param angleLow extra bits for angle (xb)
     *  @return sin(angleHigh + angleLow)
     */
    private static double sinQ(double angleHigh, double angleLow) {
        int idx = (int) ((angleHigh * 8.0) + 0.5);
        final double epsilon = angleHigh - EIGHTHS[idx];

        // Table lookups for sin(idx*PI/8) and cos(idx*PI/8)
        final double tableSinHigh = SINE_TABLE_A[idx];
        final double tableSinLow = SINE_TABLE_B[idx];
        final double tableCosHigh = COSINE_TABLE_A[idx];
        final double tableCosLow = COSINE_TABLE_B[idx];

        // Polynomial eval of sin(epsilon), cos(epsilon)
        double sinEpsilonHigh = epsilon;
        double sinEpsilonLow = polySine(epsilon);
        final double cosEpsilonHigh = 1.0;
        final double cosEpsilonLow = polyCosine(epsilon);

        // Split epsilon into high/low parts for more precise calculation
        double[] epsilonSplit = splitDouble(sinEpsilonHigh);
        sinEpsilonLow +=  sinEpsilonHigh - epsilonSplit[0];
        sinEpsilonHigh = epsilonSplit[0];

        // Compute sin(x) by angle addition formula: sin(A+B) = sin(A)cos(B) + cos(A)sin(B)
        // Here, A = (tableSinHigh + tableSinLow) and B = (sinEpsilonHigh + sinEpsilonLow)

        // Terms: tableSinHigh, tableCosHigh*sinEpsilonHigh, tableSinHigh*cosEpsilonLow, tableCosHigh*sinEpsilonLow
        //        tableSinLow, tableCosLow*sinEpsilonHigh, tableSinLow*cosEpsilonLow, tableCosLow*sinEpsilonLow

        double resultHigh = 0;
        double resultLow = 0;

        // Add tableSinHigh
        double[] sumTemp = addDoubleDouble(resultHigh, resultLow, tableSinHigh, 0.0);
        resultHigh = sumTemp[0]; resultLow = sumTemp[1];

        // Add tableCosHigh * sinEpsilonHigh
        double[] productTemp = multiplyDoubleDouble(tableCosHigh, 0.0, sinEpsilonHigh, 0.0);
        sumTemp = addDoubleDouble(resultHigh, resultLow, productTemp[0], productTemp[1]);
        resultHigh = sumTemp[0]; resultLow = sumTemp[1];

        // Accumulate remaining low-order terms directly into resultLow
        resultLow += tableSinHigh * cosEpsilonLow + tableCosHigh * sinEpsilonLow;
        resultLow += tableSinLow + tableCosLow * sinEpsilonHigh + tableSinLow * cosEpsilonLow + tableCosLow * sinEpsilonLow;

        // If there are extra bits for the angle (angleLow != 0.0), account for them
        if (angleLow != 0.0) {
            // Approximate derivative of sin(x) * angleLow = cos(x) * angleLow
            // cos(x) approx (tableCosHigh + tableCosLow) * (cosEpsilonHigh + cosEpsilonLow)
            // sin(x) approx (tableSinHigh + tableSinLow) * (sinEpsilonHigh + sinEpsilonLow)
            double cosApprox = (tableCosHigh + tableCosLow) * (cosEpsilonHigh + cosEpsilonLow) -
                               (tableSinHigh + tableSinLow) * (sinEpsilonHigh + sinEpsilonLow);
            double angleLowContribution = cosApprox * angleLow; 
            sumTemp = addDoubleDouble(resultHigh, resultLow, angleLowContribution, 0.0);
            resultHigh = sumTemp[0]; resultLow = sumTemp[1];
        }

        return resultHigh + resultLow;
    }

    /**
     * Compute cosine in the first quadrant by subtracting input from PI/2 and
     * then calling sinQ.  This is more accurate as the input approaches PI/2.
     *  @param angleHigh high bits of the angle (xa)
     *  @param angleLow extra bits for angle (xb)
     *  @return cos(angleHigh + angleLow)
     */
    private static double cosQ(double angleHigh, double angleLow) {
        final double pi2a = 1.5707963267948966; // PI/2 high bits
        final double pi2b = 6.123233995736766E-17; // PI/2 low bits

        // Calculate (PI/2 - angle) in high precision
        double remainingAngleHigh = pi2a - angleHigh;
        double remainingAngleLow = -(remainingAngleHigh - pi2a + angleHigh); // Error term
        remainingAngleLow += pi2b - angleLow;

        return sinQ(remainingAngleHigh, remainingAngleLow);
    }

    /**
     *  Compute tangent (or cotangent) over the first quadrant.   0 < x < pi/2
     *  Uses combination of table lookup and rational polynomial expansion.
     *  @param angleHigh high bits of the angle (xa)
     *  @param angleLow extra bits for angle (xb)
     *  @param cotanFlag if true, compute the cotangent instead of the tangent
     *  @return tan(angleHigh+angleLow) (or cotangent, depending on cotanFlag)
     */
    private static double tanQ(double angleHigh, double angleLow, boolean cotanFlag) {

        int idx = (int) ((angleHigh * 8.0) + 0.5);
        final double epsilon = angleHigh - EIGHTHS[idx];

        // Table lookups
        final double tableSinHigh = SINE_TABLE_A[idx];
        final double tableSinLow = SINE_TABLE_B[idx];
        final double tableCosHigh = COSINE_TABLE_A[idx];
        final double tableCosLow = COSINE_TABLE_B[idx];

        // Polynomial eval of sin(epsilon), cos(epsilon)
        double sinEpsilonHigh = epsilon;
        double sinEpsilonLow = polySine(epsilon);
        final double cosEpsilonHigh = 1.0;
        final double cosEpsilonLow = polyCosine(epsilon);

        // Split epsilon
        double[] epsilonSplit = splitDouble(sinEpsilonHigh);
        sinEpsilonLow +=  sinEpsilonHigh - epsilonSplit[0];
        sinEpsilonHigh = epsilonSplit[0];

        // Compute sine high precision (numerator)
        double sinResultHigh = 0;
        double sinResultLow = 0;

        double[] sumTemp = addDoubleDouble(sinResultHigh, sinResultLow, tableSinHigh, 0.0);
        sinResultHigh = sumTemp[0]; sinResultLow = sumTemp[1];

        double[] prodTemp = multiplyDoubleDouble(tableCosHigh, 0.0, sinEpsilonHigh, 0.0);
        sumTemp = addDoubleDouble(sinResultHigh, sinResultLow, prodTemp[0], prodTemp[1]);
        sinResultHigh = sumTemp[0]; sinResultLow = sumTemp[1];

        sinResultLow += tableSinHigh * cosEpsilonLow + tableCosHigh * sinEpsilonLow;
        sinResultLow += tableSinLow + tableCosLow * sinEpsilonHigh + tableSinLow * cosEpsilonLow + tableCosLow * sinEpsilonLow;

        // Compute cosine high precision (denominator)
        double cosResultHigh = 0.0;
        double cosResultLow = 0.0;

        prodTemp = multiplyDoubleDouble(tableCosHigh, 0.0, cosEpsilonHigh, 0.0);
        sumTemp = addDoubleDouble(cosResultHigh, cosResultLow, prodTemp[0], prodTemp[1]);
        cosResultHigh = sumTemp[0]; cosResultLow = sumTemp[1];

        prodTemp = multiplyDoubleDouble(-tableSinHigh, 0.0, sinEpsilonHigh, 0.0);
        sumTemp = addDoubleDouble(cosResultHigh, cosResultLow, prodTemp[0], prodTemp[1]);
        cosResultHigh = sumTemp[0]; cosResultLow = sumTemp[1];

        cosResultLow += tableCosLow * cosEpsilonHigh + tableCosHigh * cosEpsilonLow + tableCosLow * cosEpsilonLow;
        cosResultLow -= (tableSinLow * sinEpsilonHigh + tableSinHigh * sinEpsilonLow + tableSinLow * sinEpsilonLow);

        // If cotanFlag is true, swap sine and cosine parts
        if (cotanFlag) {
            double tempHigh = sinResultHigh; sinResultHigh = cosResultHigh; cosResultHigh = tempHigh;
            double tempLow = sinResultLow; sinResultLow = cosResultLow; cosResultLow = tempLow;
        }

        // Calculate tangent (sinResult/cosResult) with error correction
        double ratioEstimate = sinResultHigh / cosResultHigh;
        double[] ratioSplit = splitDouble(ratioEstimate);
        double ratioHigh = ratioSplit[0];
        double ratioLow = ratioSplit[1];

        ratioLow += computeDivisionCorrection(sinResultHigh, sinResultLow, cosResultHigh, cosResultLow, ratioHigh, ratioLow);

        if (angleLow != 0.0) {
            // tan'(x) = 1 + tan^2(x) ; cot'(x) = -(1 + cot^2(x))
            // Approximate impact of angleLow
            double angleLowAdjustment = angleLow * (1.0 + ratioEstimate * ratioEstimate);
            if (cotanFlag) {
                angleLowAdjustment = -angleLowAdjustment;
            }
            ratioLow += angleLowAdjustment;
        }

        return ratioHigh + ratioLow;
    }

    /**
     * Arctangent function
     *  @param x a number
     *  @return atan(x)
     */
    public static double atan(double x) {
        return atan(x, 0.0, false);
    }

    /** Internal helper function to compute arctangent.
     * @param angleHigh high bits of the number from which arctangent is requested
     * @param angleLow extra bits for x (may be 0.0)
     * @param leftPlane if true, result angle must be put in the left half plane (add PI)
     * @return atan(angleHigh + angleLow) (or angle shifted by {@code PI} if leftPlane is true)
     */
    private static double atan(double angleHigh, double angleLow, boolean leftPlane) {
        boolean negateResult = false;
        int index;

        if (angleHigh == 0.0) { // Matches +/- 0.0; return correct sign
            return leftPlane ? copySign(Math.PI, angleHigh) : angleHigh;
        }

        if (angleHigh < 0) {
            // Handle negative input by taking absolute value and negating result later
            angleHigh = -angleHigh;
            angleLow = -angleLow;
            negateResult = true;
        }

        if (angleHigh > 1.633123935319537E16) { // Very large input (approx. 2^54)
            return (negateResult ^ leftPlane) ? (-Math.PI * F_1_2) : (Math.PI * F_1_2);
        }

        // Estimate the closest tabulated arctan value, compute eps = angleHigh - tangentTable
        if (angleHigh < 1) {
            index = (int) (((-1.7168146928204136 * angleHigh * angleHigh + 8.0) * angleHigh) + 0.5);
        } else {
            final double oneOverAngleHigh = 1 / angleHigh;
            index = (int) (-((-1.7168146928204136 * oneOverAngleHigh * oneOverAngleHigh + 8.0) * oneOverAngleHigh) + 13.07);
        }

        double epsilonHigh = angleHigh - TANGENT_TABLE_A[index];
        double epsilonLow = -(epsilonHigh - angleHigh + TANGENT_TABLE_A[index]);
        epsilonLow += angleLow - TANGENT_TABLE_B[index];

        double sumEpsilon = epsilonHigh + epsilonLow;
        epsilonLow = -(sumEpsilon - epsilonHigh - epsilonLow);
        epsilonHigh = sumEpsilon; // Renormalize epsilon

        // Compute eps = epsilon / (1.0 + x*tangent_table_val)
        double[] angleSplit = splitDouble(angleHigh);
        double xHigh = angleSplit[0];
        double xLow = angleLow + angleSplit[1];

        double quotientHigh, quotientLow;
        if (index == 0) {
            // If the slope of the arctan is gentle enough (< 0.45), this approximation will suffice
            final double denominator = 1d / (1d + (xHigh + xLow) * (TANGENT_TABLE_A[index] + TANGENT_TABLE_B[index]));
            quotientHigh = epsilonHigh * denominator;
            quotientLow = epsilonLow * denominator;
        } else {
            // Denominator: 1 + x * tanTable[index]
            double prodHigh = xHigh * TANGENT_TABLE_A[index];
            double denHigh = 1d + prodHigh;
            double denLow = -(denHigh - 1d - prodHigh);
            denLow += xLow * TANGENT_TABLE_A[index] + xHigh * TANGENT_TABLE_B[index];

            double sumDen = denHigh + denLow;
            denLow += -(sumDen - denHigh - denLow);
            denHigh = sumDen; // Renormalize denominator

            denLow += xLow * TANGENT_TABLE_B[index];

            // Divide epsilon by denominator
            quotientHigh = epsilonHigh / denHigh;

            double[] quotientSplit = splitDouble(quotientHigh);
            double qHigh = quotientSplit[0];
            double qLow = quotientSplit[1];

            // Correct for rounding in division
            qLow += computeDivisionCorrection(epsilonHigh, epsilonLow, denHigh, denLow, qHigh, qLow);

            quotientHigh = qHigh;
            quotientLow = qLow;
        }

        epsilonHigh = quotientHigh;
        epsilonLow = quotientLow;

        // Evaluate polynomial for atan(epsilon)
        final double epsilonHighSquared = epsilonHigh * epsilonHigh;

        double polynomialValLow = 0.07490822288864472;
        polynomialValLow = polynomialValLow * epsilonHighSquared + -0.09088450866185192;
        polynomialValLow = polynomialValLow * epsilonHighSquared + 0.11111095942313305;
        polynomialValLow = polynomialValLow * epsilonHighSquared + -0.1428571423679182;
        polynomialValLow = polynomialValLow * epsilonHighSquared + 0.19999999999923582;
        polynomialValLow = polynomialValLow * epsilonHighSquared + -0.33333333333333287;
        polynomialValLow = polynomialValLow * epsilonHighSquared * epsilonHigh; // Multiply by epsilon^7

        // Add epsilonHigh and polynomialValLow
        double sumHigh = epsilonHigh + polynomialValLow;
        double sumLow = -(sumHigh - epsilonHigh - polynomialValLow);

        // Add in effect of epsilonLow. atan'(x) = 1/(1+x^2)
        sumLow += epsilonLow / (1d + epsilonHigh * epsilonHigh);

        // Add table value (EIGHTHS[index]) to the sum
        double finalHigh = EIGHTHS[index] + sumHigh;
        double finalLow = -(finalHigh - EIGHTHS[index] - sumHigh);
        finalLow += sumLow;

        double finalResult = finalHigh + finalLow;

        if (leftPlane) {
            // Result is in the left plane, add PI
            final double piHigh = 1.5707963267948966 * 2; // Approx PI high
            final double piLow = 6.123233995736766E-17 * 2; // Approx PI low

            double[] sumPI = addDoubleDouble(piHigh, piLow, finalHigh, finalLow);
            finalResult = sumPI[0] + sumPI[1];
        }

        return negateResult ^ leftPlane ? -finalResult : finalResult;
    }

    /**
     * Two arguments arctangent function
     * @param y ordinate
     * @param x abscissa
     * @return phase angle of point (x,y) between {@code -PI} and {@code PI}
     */
    public static double atan2(double y, double x) {
        if (Double.isNaN(x) || Double.isNaN(y)) {
            return Double.NaN;
        }

        if (y == 0) {
            final double resultSign = x * y; // preserves sign of y and makes it 0 if x is 0 or NaN
            // Special cases for Y=0:
            // atan2(0, +x) = 0 for x > 0
            // atan2(0, -x) = PI for x < 0
            // atan2(+0, -x) = +PI for x < 0
            // atan2(-0, -x) = -PI for x < 0
            // atan2(0, +-0) = 0 (sign of y)

            if (x > 0 || (x == 0 && resultSign >= 0)) { // x is positive or +0
                return resultSign; // +0 or -0
            }
            return copySign(Math.PI, y); // x is negative or -0, return +/-PI
        }

        // y cannot now be zero

        if (y == Double.POSITIVE_INFINITY) {
            if (x == Double.POSITIVE_INFINITY) {
                return Math.PI * F_1_4; // atan2(inf, inf) = PI/4
            }
            if (x == Double.NEGATIVE_INFINITY) {
                return Math.PI * F_3_4; // atan2(inf, -inf) = 3*PI/4
            }
            return Math.PI * F_1_2; // atan2(inf, x) = PI/2 for finite x
        }

        if (y == Double.NEGATIVE_INFINITY) {
            if (x == Double.POSITIVE_INFINITY) {
                return -Math.PI * F_1_4; // atan2(-inf, inf) = -PI/4
            }
            if (x == Double.NEGATIVE_INFINITY) {
                return -Math.PI * F_3_4; // atan2(-inf, -inf) = -3*PI/4
            }
            return -Math.PI * F_1_2; // atan2(-inf, x) = -PI/2 for finite x
        }

        if (x == Double.POSITIVE_INFINITY) {
            // atan2(y, inf) = 0 for y > 0
            // atan2(y, inf) = -0 for y < 0
            return copySign(0d, y);
        }

        if (x == Double.NEGATIVE_INFINITY) {
            // atan2(y, -inf) = PI for y > 0
            // atan2(y, -inf) = -PI for y < 0
            return copySign(Math.PI, y);
        }

        // Neither y nor x can be infinite or NaN here

        if (x == 0) {
            // atan2(y, 0) = PI/2 for y > 0
            // atan2(y, 0) = -PI/2 for y < 0
            return copySign(Math.PI * F_1_2, y);
        }

        // Compute ratio r = y/x
        final double ratioEstimate = y / x;
        if (Double.isInfinite(ratioEstimate)) { // bypass calculations that can create NaN (e.g., if x is subnormal and y is normal)
            return atan(ratioEstimate, 0, x < 0);
        }

        double[] ratioSplit = splitDouble(ratioEstimate);
        double ratioHigh = ratioSplit[0];
        double ratioLow = ratioSplit[1];

        // Split x into xHigh and xLow
        final double[] xSplit = splitDouble(x);
        final double xHigh = xSplit[0];
        final double xLow = xSplit[1];

        // Refine ratioLow for more precision
        ratioLow += computeDivisionCorrection(y, 0.0, xHigh, xLow, ratioHigh, ratioLow);

        // Re-combine ratioHigh and ratioLow to ensure renormalization if needed (though atan does this too)
        double[] finalRatioPrec = addDoubleDouble(ratioHigh, ratioLow, 0.0, 0.0);
        ratioHigh = finalRatioPrec[0];
        ratioLow = finalRatioPrec[1];

        if (ratioHigh == 0) { // Fix up the sign so atan works correctly with signed zero
            ratioHigh = copySign(0d, y);
        }

        // Call atan(ratio, error, x_is_negative)
        return atan(ratioHigh, ratioLow, x < 0);
    }

    /** Compute the arc sine of a number.
     * @param x number on which evaluation is done
     * @return arc sine of x
     */
    public static double asin(double x) {
      if (Double.isNaN(x)) {
          return Double.NaN;
      }

      if (x > 1.0 || x < -1.0) {
          return Double.NaN;
      }

      if (x == 1.0) {
          return Math.PI/2.0;
      }

      if (x == -1.0) {
          return -Math.PI/2.0;
      }

      if (x == 0.0) { // Matches +/- 0.0; return correct sign
          return x;
      }

      // Compute asin(x) = atan(x/sqrt(1-x*x))

      // Split x into xHigh and xLow
      double[] xSplit = splitDouble(x);
      final double xHigh = xSplit[0];
      final double xLow = xSplit[1];

      // Square x: x^2 = (xHigh+xLow)^2 = xHigh^2 + 2*xHigh*xLow + xLow^2
      double xSquaredHigh = xHigh * xHigh;
      double xSquaredLow = xHigh * xLow * 2.0 + xLow * xLow;

      // Subtract from 1: (1 - x^2)
      double oneMinusX2High = 1.0 - xSquaredHigh;
      double oneMinusX2Low = -(oneMinusX2High - 1.0 + xSquaredHigh); // Error from 1 - xSquaredHigh
      oneMinusX2Low += -xSquaredLow; // Add effect of xSquaredLow

      // Renormalize (1 - x^2)
      double[] oneMinusX2Prec = addDoubleDouble(oneMinusX2High, oneMinusX2Low, 0.0, 0.0);
      oneMinusX2High = oneMinusX2Prec[0];
      oneMinusX2Low = oneMinusX2Prec[1];

      // Square root of (1 - x^2)
      double sqrtValEstimate = sqrt(oneMinusX2High); // First estimate from high part
      double[] sqrtValSplit = splitDouble(sqrtValEstimate);
      double sqrtValHigh = sqrtValSplit[0];
      double sqrtValLow = sqrtValSplit[1];

      // Extend precision of sqrt using Newton-Raphson-like step: y = y_old + (N - y_old^2) / (2*y_old)
      double squareError = oneMinusX2High - sqrtValHigh*sqrtValHigh - 2*sqrtValHigh*sqrtValLow - sqrtValLow*sqrtValLow;
      sqrtValLow += squareError / (2.0 * sqrtValEstimate);

      // Contribution of oneMinusX2Low to sqrt
      sqrtValLow += oneMinusX2Low / (2.0 * sqrtValEstimate);

      // Recombine sqrt components
      double[] sqrtFinalPrec = addDoubleDouble(sqrtValHigh, sqrtValLow, 0.0, 0.0);
      sqrtValHigh = sqrtFinalPrec[0];
      sqrtValLow = sqrtFinalPrec[1];

      // Compute ratio r = x / sqrt(1 - x^2)
      double ratioEstimate = x / sqrtValHigh;
      double[] ratioSplit = splitDouble(ratioEstimate);
      double ratioHigh = ratioSplit[0];
      double ratioLow = ratioSplit[1];

      // Correct for rounding in division and add effects of low parts
      ratioLow += computeDivisionCorrection(xHigh, xLow, sqrtValHigh, sqrtValLow, ratioHigh, ratioLow);

      // Recombine ratio components for final atan call
      double[] finalRatioPrec = addDoubleDouble(ratioHigh, ratioLow, 0.0, 0.0);
      ratioHigh = finalRatioPrec[0];
      ratioLow = finalRatioPrec[1];

      return atan(ratioHigh, ratioLow, false);
    }

    /** Compute the arc cosine of a number.
     * @param x number on which evaluation is done
     * @return arc cosine of x
     */
    public static double acos(double x) {
      if (Double.isNaN(x)) {
          return Double.NaN;
      }

      if (x > 1.0 || x < -1.0) {
          return Double.NaN;
      }

      if (x == -1.0) {
          return Math.PI;
      }

      if (x == 1.0) {
          return 0.0;
      }

      if (x == 0) {
          return Math.PI/2.0;
      }

      // Compute acos(x) = atan(sqrt(1-x*x)/x)

      // Split x
      double[] xSplit = splitDouble(x);
      final double xHigh = xSplit[0];
      final double xLow = xSplit[1];

      // Square x
      double xSquaredHigh = xHigh * xHigh;
      double xSquaredLow = xHigh * xLow * 2.0 + xLow * xLow;

      // Subtract from 1
      double oneMinusX2High = 1.0 - xSquaredHigh;
      double oneMinusX2Low = -(oneMinusX2High - 1.0 + xSquaredHigh);
      oneMinusX2Low += -xSquaredLow;

      // Renormalize (1 - x^2)
      double[] oneMinusX2Prec = addDoubleDouble(oneMinusX2High, oneMinusX2Low, 0.0, 0.0);
      oneMinusX2High = oneMinusX2Prec[0];
      oneMinusX2Low = oneMinusX2Prec[1];

      // Square root of (1 - x^2)
      double sqrtValEstimate = sqrt(oneMinusX2High);
      double[] sqrtValSplit = splitDouble(sqrtValEstimate);
      double sqrtValHigh = sqrtValSplit[0];
      double sqrtValLow = sqrtValSplit[1];

      // Extend precision of sqrt
      double squareError = oneMinusX2High - sqrtValHigh*sqrtValHigh - 2*sqrtValHigh*sqrtValLow - sqrtValLow*sqrtValLow;
      sqrtValLow += squareError / (2.0 * sqrtValEstimate);

      // Contribution of oneMinusX2Low to sqrt
      sqrtValLow += oneMinusX2Low / (2.0 * sqrtValEstimate);

      // Recombine sqrt components
      double[] sqrtFinalPrec = addDoubleDouble(sqrtValHigh, sqrtValLow, 0.0, 0.0);
      sqrtValHigh = sqrtFinalPrec[0];
      sqrtValLow = sqrtFinalPrec[1];

      // Compute ratio r = sqrt(1-x*x) / x
      double ratioEstimate = sqrtValHigh / x;

      // Did r overflow? (i.e., x is effectively zero)
      if (Double.isInfinite(ratioEstimate)) {
          return Math.PI/2; // so return the appropriate value
      }

      double[] ratioSplit = splitDouble(ratioEstimate);
      double ratioHigh = ratioSplit[0];
      double ratioLow = ratioSplit[1];

      // Correct for rounding in division and add effects of low parts
      ratioLow += computeDivisionCorrection(sqrtValHigh, sqrtValLow, xHigh, xLow, ratioHigh, ratioLow);

      // Recombine ratio components for final atan call
      double[] finalRatioPrec = addDoubleDouble(ratioHigh, ratioLow, 0.0, 0.0);
      ratioHigh = finalRatioPrec[0];
      ratioLow = finalRatioPrec[1];

      return atan(ratioHigh, ratioLow, x < 0);
    }

    /** Compute the cubic root of a number.
     * @param x number on which evaluation is done
     * @return cubic root of x
     */
    public static double cbrt(double x) {
      // Convert input double to bits
      long inbits = Double.doubleToRawLongBits(x);
      int exponent = (int) ((inbits >> 52) & 0x7ff) - 1023;
      boolean subnormal = false;

      if (exponent == -1023) {
          if (x == 0) {
              return x;
          }

          // Subnormal, so normalize
          subnormal = true;
          x *= 1.8014398509481984E16;  // 2^54 (shift up 54 bits)
          inbits = Double.doubleToRawLongBits(x);
          exponent = (int) ((inbits >> 52) & 0x7ff) - 1023;
      }

      if (exponent == 1024) {
          // NaN or infinity. Don't care which, return as is.
          return x;
      }

      // Divide the exponent by 3
      int expDiv3 = exponent / 3;

      // p2 will be the nearest power of 2 to x with its exponent divided by 3
      // This creates 2^(expDiv3) multiplied by the sign bit.
      double p2 = Double.longBitsToDouble((inbits & 0x8000000000000000L) | (((long)(expDiv3 + 1023)) << 52));

      // mantissa will be a number between 1 and 2 (normalized mantissa)
      final double mantissa = Double.longBitsToDouble((inbits & 0x000fffffffffffffL) | 0x3ff0000000000000L);

      // Estimate the cube root of mantissa by polynomial approximation
      double estimate = -0.010714690733195933;
      estimate = estimate * mantissa + 0.0875862700108075;
      estimate = estimate * mantissa + -0.3058015757857271;
      estimate = estimate * mantissa + 0.7249995199969751;
      estimate = estimate * mantissa + 0.5039018405998233;

      // Adjust estimate based on the remainder of exponent % 3 (i.e., 2^(0/3), 2^(1/3), 2^(2/3))
      // CBRTTWO has 2^( (n+2)/3 ) for n=0,1,2,3,4. Original uses index (exponent % 3 + 2).
      // For exp%3 = 0, idx=2 => 2^(2/3) => 1.0
      // For exp%3 = 1, idx=3 => 2^(3/3) => 1.259...
      // For exp%3 = 2, idx=4 => 2^(4/3) => 1.587...
      estimate *= CBRTTWO[exponent % 3 + 2];

      // Estimate should now be good to about 15 bits of precision.
      // Do 2 rounds of Newton's method (x_new = x_old - f(x_old)/f'(x_old) for f(y)=y^3-N => y_new = y_old - (y_old^3 - N)/(3*y_old^2))
      // Scale down x for Newton's method to avoid over/underflows.
      final double scaledX = x / (p2 * p2 * p2); // effectively x / (2^(expDiv3))^3 = x / 2^(expDiv3 * 3)
      estimate += (scaledX - estimate * estimate * estimate) / (3 * estimate * estimate);
      estimate += (scaledX - estimate * estimate * estimate) / (3 * estimate * estimate);

      // Do one round of Newton's method in extended precision to get the last bit right.
      double[] estimateSplit = splitDouble(estimate);
      double estimateHigh = estimateSplit[0];
      double estimateLow = estimateSplit[1];

      // Compute estimate^2 in high precision
      double estimateSquaredHigh = estimateHigh * estimateHigh;
      double estimateSquaredLow = estimateHigh * estimateLow * 2.0 + estimateLow * estimateLow;

      // Compute estimate^3 in high precision (estimate^2 * estimate)
      double estimateCubedHigh = estimateSquaredHigh * estimateHigh;
      double estimateCubedLow = estimateSquaredHigh * estimateLow + estimateSquaredLow * estimateHigh + estimateSquaredLow * estimateLow;

      // Compute (scaledX - estimate^3) in high precision (numerator of Newton step)
      double numeratorHigh = scaledX - estimateCubedHigh;
      double numeratorLow = -(numeratorHigh - scaledX + estimateCubedHigh);
      numeratorLow -= estimateCubedLow;

      // Denominator of Newton step is 3 * estimate^2
      double denominatorNewton = 3 * estimate * estimate;

      estimate += (numeratorHigh + numeratorLow) / denominatorNewton;

      // Scale by a power of two, so this is exact (undo original scaling by p2).
      estimate *= p2;

      if (subnormal) {
          estimate *= 3.814697265625E-6;  // Compensate for initial 2^54 shift: 2^-18, since 2^54 / 2^3 = 2^18
      }

      return estimate;
    }

    /**
     *  Convert degrees to radians, with error of less than 0.5 ULP
     *  @param x angle in degrees
     *  @return x converted into radians
     */
    public static double toRadians(double x)
    {
        if (Double.isInfinite(x) || x == 0.0) { // Matches +/- 0.0; return correct sign
            return x;
        }

        // These are PI/180 split into high and low order bits
        final double factorHigh = 0.01745329052209854; // pi/180 high
        final double factorLow = 1.997844754509471E-9; // pi/180 low

        // Split input x into high and low parts
        double[] xSplit = splitDouble(x);
        double xHigh = xSplit[0];
        double xLow = xSplit[1];

        // Perform (xHigh + xLow) * (factorHigh + factorLow) using binomial expansion
        double result = xLow * factorLow + xLow * factorHigh + xHigh * factorLow + xHigh * factorHigh;

        if (result == 0) {
            // Ensure correct sign if calculation underflows to zero
            result = result * x; 
        }
        return result;
    }

    /**
     *  Convert radians to degrees, with error of less than 0.5 ULP
     *  @param x angle in radians
     *  @return x converted into degrees
     */
    public static double toDegrees(double x)
    {
        if (Double.isInfinite(x) || x == 0.0) { // Matches +/- 0.0; return correct sign
            return x;
        }

        // These are 180/PI split into high and low order bits
        final double factorHigh = 57.2957763671875; // 180/pi high
        final double factorLow = 3.145894820876798E-6; // 180/pi low

        // Split input x into high and low parts
        double[] xSplit = splitDouble(x);
        double xHigh = xSplit[0];
        double xLow = xSplit[1];

        // Perform (xHigh + xLow) * (factorHigh + factorLow) using binomial expansion
        return xLow * factorLow + xLow * factorHigh + xHigh * factorLow + xHigh * factorHigh;
    }

    /**
     * Absolute value.
     * @param x number from which absolute value is requested
     * @return abs(x)
     */
    public static int abs(final int x) {
        // Bit manipulation to compute absolute value without branching
        // int i = x >>> 31; // 0 if positive/zero, 1 if negative
        // return (x ^ (~i + 1)) + i; // (x XOR (-i)) + i
        // For negative x, ~i+1 is -1. x ^ -1 is ~x. ~x + 1 is -x.
        // For positive x, ~i+1 is 0. x ^ 0 is x. x + 0 is x.
        final int mask = x >> 31; // All 1s if negative, all 0s if positive/zero
        return (x + mask) ^ mask; // (x + mask) flips bits if negative, then XOR with mask flips them back
    }

    /**
     * Absolute value.
     * @param x number from which absolute value is requested
     * @return abs(x)
     */
    public static long abs(final long x) {
        // Similar bit manipulation for long
        final long mask = x >> 63; // All 1s if negative, all 0s if positive/zero
        return (x + mask) ^ mask;
    }

    /**
     * Absolute value.
     * @param x number from which absolute value is requested
     * @return abs(x)
     */
    public static float abs(final float x) {
        // Clear the sign bit (most significant bit)
        return Float.intBitsToFloat(MASK_NON_SIGN_INT & Float.floatToRawIntBits(x));
    }

    /**
     * Absolute value.
     * @param x number from which absolute value is requested
     * @return abs(x)
     */
    public static double abs(double x) {
        // Clear the sign bit (most significant bit)
        return Double.longBitsToDouble(MASK_NON_SIGN_LONG & Double.doubleToRawLongBits(x));
    }

    /**
     * Compute least significant bit (Unit in Last Position) for a number.
     * @param x number from which ulp is requested
     * @return ulp(x)
     */
    public static double ulp(double x) {
        if (Double.isInfinite(x)) {
            return Double.POSITIVE_INFINITY;
        }
        // ulp(x) is the distance between x and the next representable floating-point number
        // If x is positive, it's x - nextDown(x).
        // If x is negative, it's nextUp(x) - x.
        // Can be simplified to abs(x - (x changed by 1 in lowest bit))
        return abs(x - Double.longBitsToDouble(Double.doubleToRawLongBits(x) ^ 1L));
    }

    /**
     * Compute least significant bit (Unit in Last Position) for a number.
     * @param x number from which ulp is requested
     * @return ulp(x)
     */
    public static float ulp(float x) {
        if (Float.isInfinite(x)) {
            return Float.POSITIVE_INFINITY;
        }
        return abs(x - Float.intBitsToFloat(Float.floatToIntBits(x) ^ 1));
    }

    /**
     * Multiply a double number by a power of 2.
     * @param d number to multiply
     * @param n power of 2
     * @return d &times; 2<sup>n</sup>
     */
    public static double scalb(final double d, final int n) {

        // First simple and fast handling when 2^n can be represented using normal numbers
        // Exponent bias for double is 1023. n + 1023 gives the biased exponent for 2^n.
        // 2^n is (biased_exponent << 52).
        if ((n > -1023) && (n < 1024)) {
            return d * Double.longBitsToDouble(((long) (n + 1023)) << 52);
        }

        // Handle special cases
        if (Double.isNaN(d) || Double.isInfinite(d) || (d == 0)) {
            return d;
        }

        // Handle cases where n is too small to keep number from underflowing to 0
        if (n < -2098) { // -1023 (normal min exponent) - 52 (mantissa bits) - 23 (safety margin)
            return (d > 0) ? 0.0 : -0.0;
        }

        // Handle cases where n is too large to keep number from overflowing to infinity
        if (n > 2097) { // 1024 (normal max exponent) + 52 (mantissa bits) + 21 (safety margin)
            return (d > 0) ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
        }

        // Decompose d into sign, exponent, mantissa
        final long bits = Double.doubleToRawLongBits(d);
        final long sign = bits & 0x8000000000000000L;
        int  exponent   = ((int) (bits >>> 52)) & 0x7ff; // Biased exponent
        long mantissa   = bits & 0x000fffffffffffffL; // Implicit leading 1 not included here

        // Compute scaled exponent
        int scaledExponent = exponent + n;

        if (n < 0) { // Scaling down (n is negative)
            // We are effectively in the case n <= -1023 based on the initial fast path
            if (scaledExponent > 0) {
                // Both the input and the result are normal numbers, we only adjust the exponent
                return Double.longBitsToDouble(sign | (((long) scaledExponent) << 52) | mantissa);
            } else if (scaledExponent > -53) { // scaledExponent is between -52 and 0 (inclusive)
                // The input is a normal number and the result is a subnormal number

                // Recover the hidden mantissa bit (2^52 is implicit 1)
                mantissa = mantissa | (1L << 52);

                // Scales down complete mantissa, potentially losing least significant bits.
                // 1 - scaledExponent is the total shift needed. (e.g., if scaledExponent is -50, shift by 51)
                final long mostSignificantLostBit = mantissa & (1L << (-scaledExponent)); // Bit to check for rounding
                mantissa = mantissa >>> (1 - scaledExponent); // Shift mantissa right
                if (mostSignificantLostBit != 0) {
                    // If the most significant bit lost was 1, we round up.
                    mantissa++;
                }
                return Double.longBitsToDouble(sign | mantissa);

            } else {
                // No need to compute the mantissa, the number scales down to 0
                return (sign == 0L) ? 0.0 : -0.0;
            }
        } else { // Scaling up (n is positive)
            // We are effectively in the case n >= 1024 based on the initial fast path
            if (exponent == 0) { // Input number is subnormal (exponent is 0, mantissa is non-zero)
                // Normalize the subnormal number first
                while ((mantissa >>> 52) != 1) { // Shift left until hidden bit is at 52
                    mantissa = mantissa << 1;
                    --scaledExponent; // Decrement exponent for each left shift
                }
                ++scaledExponent; // Account for the implicit 1 becoming explicit
                mantissa = mantissa & 0x000fffffffffffffL; // Clear the explicit 1 for normal representation

                if (scaledExponent < 2047) { // Check if new exponent is still within normal range
                    return Double.longBitsToDouble(sign | (((long) scaledExponent) << 52) | mantissa);
                } else { // Overflow to infinity
                    return (sign == 0L) ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
                }

            } else if (scaledExponent < 2047) { // Result is a normal number
                return Double.longBitsToDouble(sign | (((long) scaledExponent) << 52) | mantissa);
            } else { // Overflow to infinity
                return (sign == 0L) ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
            }
        }

    }

    /**
     * Multiply a float number by a power of 2.
     * @param f number to multiply
     * @param n power of 2
     * @return f &times; 2<sup>n</sup>
     */
    public static float scalb(final float f, final int n) {

        // First simple and fast handling when 2^n can be represented using normal numbers
        // Exponent bias for float is 127.
        if ((n > -127) && (n < 128)) {
            return f * Float.intBitsToFloat((n + 127) << 23);
        }

        // Handle special cases
        if (Float.isNaN(f) || Float.isInfinite(f) || (f == 0f)) {
            return f;
        }

        // Handle cases where n is too small to keep number from underflowing to 0
        if (n < -277) { // -127 (normal min exponent) - 23 (mantissa bits) - 127 (min exponent of subnormal)
            return (f > 0) ? 0.0f : -0.0f;
        }

        // Handle cases where n is too large to keep number from overflowing to infinity
        if (n > 276) { // 128 (normal max exponent) + 23 (mantissa bits) + 127 (max exponent)
            return (f > 0) ? Float.POSITIVE_INFINITY : Float.NEGATIVE_INFINITY;
        }

        // Decompose f into sign, exponent, mantissa
        final int bits = Float.floatToIntBits(f);
        final int sign = bits & 0x80000000;
        int  exponent  = (bits >>> 23) & 0xff; // Biased exponent
        int mantissa   = bits & 0x007fffff; // Implicit leading 1 not included here

        // Compute scaled exponent
        int scaledExponent = exponent + n;

        if (n < 0) { // Scaling down (n is negative)
            // We are effectively in the case n <= -127 based on the initial fast path
            if (scaledExponent > 0) {
                // Both the input and the result are normal numbers, we only adjust the exponent
                return Float.intBitsToFloat(sign | (scaledExponent << 23) | mantissa);
            } else if (scaledExponent > -24) { // scaledExponent is between -23 and 0 (inclusive)
                // The input is a normal number and the result is a subnormal number

                // Recover the hidden mantissa bit (2^23 is implicit 1)
                mantissa = mantissa | (1 << 23);

                // Scales down complete mantissa, potentially losing least significant bits
                final int mostSignificantLostBit = mantissa & (1 << (-scaledExponent));
                mantissa = mantissa >>> (1 - scaledExponent);
                if (mostSignificantLostBit != 0) {
                    // If the most significant bit lost was 1, we round up.
                    mantissa++;
                }
                return Float.intBitsToFloat(sign | mantissa);

            } else {
                // No need to compute the mantissa, the number scales down to 0
                return (sign == 0) ? 0.0f : -0.0f;
            }
        } else { // Scaling up (n is positive)
            // We are effectively in the case n >= 128 based on the initial fast path
            if (exponent == 0) { // Input number is subnormal

                // Normalize the subnormal number first
                while ((mantissa >>> 23) != 1) {
                    mantissa = mantissa << 1;
                    --scaledExponent;
                }
                ++scaledExponent; // Account for the implicit 1 becoming explicit
                mantissa = mantissa & 0x007fffff; // Clear the explicit 1 for normal representation

                if (scaledExponent < 255) {
                    return Float.intBitsToFloat(sign | (scaledExponent << 23) | mantissa);
                } else {
                    return (sign == 0) ? Float.POSITIVE_INFINITY : Float.NEGATIVE_INFINITY;
                }

            } else if (scaledExponent < 255) { // Result is a normal number
                return Float.intBitsToFloat(sign | (scaledExponent << 23) | mantissa);
            } else {
                return (sign == 0) ? Float.POSITIVE_INFINITY : Float.NEGATIVE_INFINITY;
            }
        }
    }

    /**
     * Get the next machine representable number after a number, moving
     * in the direction of another number.
     * <p>
     * The ordering is as follows (increasing):
     * <ul>
     * <li>-INFINITY</li>
     * <li>-MAX_VALUE</li>
     * <li>-MIN_VALUE</li>
     * <li>-0.0</li>
     * <li>+0.0</li>
     * <li>+MIN_VALUE</li>
     * <li>+MAX_VALUE</li>
     * <li>+INFINITY</li>
     * </ul>
     * <p>
     * If arguments compare equal, then the second argument is returned.
     * <p>
     * If {@code direction} is greater than {@code d},
     * the smallest machine representable number strictly greater than
     * {@code d} is returned; if less, then the largest representable number
     * strictly less than {@code d} is returned.</p>
     * <p>
     * If {@code d} is infinite and direction does not
     * bring it back to finite numbers, it is returned unchanged.</p>
     *
     * @param d base number
     * @param direction (the only important thing is whether
     * {@code direction} is greater or smaller than {@code d})
     * @return the next machine representable number in the specified direction
     */
    public static double nextAfter(double d, double direction) {

        // Handling of some important special cases
        if (Double.isNaN(d) || Double.isNaN(direction)) {
            return Double.NaN;
        } else if (d == direction) {
            return direction;
        } else if (Double.isInfinite(d)) {
            return (d < 0) ? -Double.MAX_VALUE : Double.MAX_VALUE;
        } else if (d == 0) {
            return (direction < 0) ? -Double.MIN_VALUE : Double.MIN_VALUE;
        }
        // Special cases like MAX_VALUE to infinity and MIN_VALUE to 0
        // are handled just as normal numbers using bit manipulation.
        // We can use raw bits since infinity and NaN are already dealt with.
        final long bits = Double.doubleToRawLongBits(d);
        final long signBit = bits & 0x8000000000000000L;

        if ((direction < d) ^ (signBit == 0L)) {
            // If direction is smaller than d, and d is positive OR
            // If direction is greater than d, and d is negative (effectively moving towards 0 or positive direction)
            // -> Decrement the absolute value of the number's bit representation
            return Double.longBitsToDouble(signBit | ((bits & MASK_NON_SIGN_LONG) - 1));
        } else {
            // If direction is greater than d, and d is positive OR
            // If direction is smaller than d, and d is negative (effectively moving away from 0 or negative direction)
            // -> Increment the absolute value of the number's bit representation
            return Double.longBitsToDouble(signBit | ((bits & MASK_NON_SIGN_LONG) + 1));
        }
    }

    /**
     * Get the next machine representable number after a number, moving
     * in the direction of another number.
     * <p>
     * The ordering is as follows (increasing):
     * <ul>
     * <li>-INFINITY</li>
     * <li>-MAX_VALUE</li>
     * <li>-MIN_VALUE</li>
     * <li>-0.0</li>
     * <li>+0.0</li>
     * <li>+MIN_VALUE</li>
     * <li>+MAX_VALUE</li>
     * <li>+INFINITY</li>
     * </ul>
     * <p>
     * If arguments compare equal, then the second argument is returned.
     * <p>
     * If {@code direction} is greater than {@code f},
     * the smallest machine representable number strictly greater than
     * {@code f} is returned; if less, then the largest representable number
     * strictly less than {@code f} is returned.</p>
     * <p>
     * If {@code f} is infinite and direction does not
     * bring it back to finite numbers, it is returned unchanged.</p>
     *
     * @param f base number
     * @param direction (the only important thing is whether
     * {@code direction} is greater or smaller than {@code f})
     * @return the next machine representable number in the specified direction
     */
    public static float nextAfter(final float f, final double direction) {

        // Handling of some important special cases
        if (Float.isNaN(f) || Double.isNaN(direction)) {
            return Float.NaN;
        } else if (f == direction) {
            return (float) direction;
        } else if (Float.isInfinite(f)) {
            return (f < 0f) ? -Float.MAX_VALUE : Float.MAX_VALUE;
        } else if (f == 0f) {
            return (direction < 0) ? -Float.MIN_VALUE : Float.MIN_VALUE;
        }
        // Special cases like MAX_VALUE to infinity and MIN_VALUE to 0
        // are handled just as normal numbers using bit manipulation.

        final int bits = Float.floatToIntBits(f);
        final int signBit = bits & 0x80000000;

        if ((direction < f) ^ (signBit == 0)) {
            // Decrement the absolute value of the number's bit representation
            return Float.intBitsToFloat(signBit | ((bits & MASK_NON_SIGN_INT) - 1));
        } else {
            // Increment the absolute value of the number's bit representation
            return Float.intBitsToFloat(signBit | ((bits & MASK_NON_SIGN_INT) + 1));
        }
    }

    /** Get the largest whole number smaller than x.
     * @param x number from which floor is requested
     * @return a double number f such that f is an integer f <= x < f + 1.0
     */
    public static double floor(double x) {
        long integerPart;

        if (Double.isNaN(x)) { // NaN
            return x;
        }

        if (x >= TWO_POWER_52 || x <= -TWO_POWER_52) {
            // Numbers this large are already integers or too large for fractional part to matter
            return x;
        }

        integerPart = (long) x;
        if (x < 0 && integerPart != x) { // If x is negative and has a fractional part
            integerPart--; // Round down to the next smaller integer
        }

        if (integerPart == 0) {
            return x * integerPart; // Handles +0.0 and -0.0 correctly
        }

        return integerPart;
    }

    /** Get the smallest whole number larger than x.
     * @param x number from which ceil is requested
     * @return a double number c such that c is an integer c - 1.0 < x <= c
     */
    public static double ceil(double x) {
        double floorVal;

        if (Double.isNaN(x)) { // NaN
            return x;
        }

        floorVal = floor(x);
        if (floorVal == x) { // x is already an integer
            return floorVal;
        }

        floorVal += 1.0; // Increment to the next integer

        if (floorVal == 0) {
            return x * floorVal; // Handles +0.0 and -0.0 correctly
        }

        return floorVal;
    }

    /** Get the whole number that is the nearest to x, or the even one if x is exactly half way between two integers.
     * @param x number from which nearest whole number is requested
     * @return a double number r such that r is an integer r - 0.5 <= x <= r + 0.5
     */
    public static double rint(double x) {
        double floorVal = floor(x);
        double fractionalPart = x - floorVal;

        if (fractionalPart > 0.5) {
            if (floorVal == -1.0) {
                return -0.0; // Preserve sign for -0.5 cases rounding to 0
            }
            return floorVal + 1.0;
        }
        if (fractionalPart < 0.5) {
            return floorVal;
        }

        // Half way case (fractionalPart == 0.5), round to even integer
        long integerPart = (long) floorVal;
        return (integerPart & 1) == 0 ? floorVal : floorVal + 1.0; // If even, return floorVal; otherwise round up
    }

    /** Get the closest long to x.
     * @param x number from which closest long is requested
     * @return closest long to x
     */
    public static long round(double x) {
        return (long) floor(x + 0.5); // Add 0.5 and then take floor (truncates toward negative infinity)
    }

    /** Get the closest int to x.
     * @param x number from which closest int is requested
     * @return closest int to x
     */
    public static int round(final float x) {
        return (int) floor(x + 0.5f);
    }

    /** Compute the minimum of two values
     * @param a first value
     * @param b second value
     * @return a if a is lesser or equal to b, b otherwise
     */
    public static int min(final int a, final int b) {
        return (a <= b) ? a : b;
    }

    /** Compute the minimum of two values
     * @param a first value
     * @param b second value
     * @return a if a is lesser or equal to b, b otherwise
     */
    public static long min(final long a, final long b) {
        return (a <= b) ? a : b;
    }

    /** Compute the minimum of two values
     * @param a first value
     * @param b second value
     * @return a if a is lesser or equal to b, b otherwise
     */
    public static float min(final float a, final float b) {
        if (a > b) {
            return b;
        }
        if (a < b) {
            return a;
        }
        /* if either arg is NaN, return NaN */
        if (a != b) { // True if a is NaN or b is NaN (or both)
            return Float.NaN;
        }
        /* min(+0.0,-0.0) == -0.0 */
        /* 0x80000000 == Float.floatToRawIntBits(-0.0d) */
        int bits = Float.floatToRawIntBits(a);
        if (bits == 0x80000000) { // If a is -0.0
            return a;
        }
        return b; // If a is +0.0 (and b is +0.0), return b (+0.0)
    }

    /** Compute the minimum of two values
     * @param a first value
     * @param b second value
     * @return a if a is lesser or equal to b, b otherwise
     */
    public static double min(final double a, final double b) {
        if (a > b) {
            return b;
        }
        if (a < b) {
            return a;
        }
        /* if either arg is NaN, return NaN */
        if (a != b) { // True if a is NaN or b is NaN (or both)
            return Double.NaN;
        }
        /* min(+0.0,-0.0) == -0.0 */
        /* 0x8000000000000000L == Double.doubleToRawLongBits(-0.0d) */
        long bits = Double.doubleToRawLongBits(a);
        if (bits == 0x8000000000000000L) { // If a is -0.0
            return a;
        }
        return b; // If a is +0.0 (and b is +0.0), return b (+0.0)
    }

    /** Compute the maximum of two values
     * @param a first value
     * @param b second value
     * @return b if a is lesser or equal to b, a otherwise
     */
    public static int max(final int a, final int b) {
        return (a <= b) ? b : a;
    }

    /** Compute the maximum of two values
     * @param a first value
     * @param b second value
     * @return b if a is lesser or equal to b, a otherwise
     */
    public static long max(final long a, final long b) {
        return (a <= b) ? b : a;
    }

    /** Compute the maximum of two values
     * @param a first value
     * @param b second value
     * @return b if a is lesser or equal to b, a otherwise
     */
    public static float max(final float a, final float b) {
        if (a > b) {
            return a;
        }
        if (a < b) {
            return b;
        }
        /* if either arg is NaN, return NaN */
        if (a != b) {
            return Float.NaN;
        }
        /* max(+0.0,-0.0) == +0.0 */
        /* 0x80000000 == Float.floatToRawIntBits(-0.0d) */
        int bits = Float.floatToRawIntBits(a);
        if (bits == 0x80000000) { // If a is -0.0
            return b; // return b (+0.0)
        }
        return a; // If a is +0.0 (and b is +0.0), return a (+0.0)
    }

    /** Compute the maximum of two values
     * @param a first value
     * @param b second value
     * @return b if a is lesser or equal to b, a otherwise
     */
    public static double max(final double a, final double b) {
        if (a > b) {
            return a;
        }
        if (a < b) {
            return b;
        }
        /* if either arg is NaN, return NaN */
        if (a != b) {
            return Double.NaN;
        }
        /* max(+0.0,-0.0) == +0.0 */
        /* 0x8000000000000000L == Double.doubleToRawLongBits(-0.0d) */
        long bits = Double.doubleToRawLongBits(a);
        if (bits == 0x8000000000000000L) { // If a is -0.0
            return b; // return b (+0.0)
        }
        return a; // If a is +0.0 (and b is +0.0), return a (+0.0)
    }

    /**
     * Returns the hypotenuse of a triangle with sides {@code x} and {@code y}
     * - sqrt(<i>x</i><sup>2</sup>&nbsp;+<i>y</i><sup>2</sup>)<br/>
     * avoiding intermediate overflow or underflow.
     *
     * <ul>
     * <li> If either argument is infinite, then the result is positive infinity.</li>
     * <li> else, if either argument is NaN then the result is NaN.</li>
     * </ul>
     *
     * @param x a value
     * @param y a value
     * @return sqrt(<i>x</i><sup>2</sup>&nbsp;+<i>y</i><sup>2</sup>)
     */
    public static double hypot(final double x, final double y) {
        if (Double.isInfinite(x) || Double.isInfinite(y)) {
            return Double.POSITIVE_INFINITY;
        } else if (Double.isNaN(x) || Double.isNaN(y)) {
            return Double.NaN;
        } else {

            final int expX = getExponent(x);
            final int expY = getExponent(y);
            if (expX > expY + 27) {
                // y is neglectible with respect to x (more than 27 orders of magnitude difference in exponent)
                return abs(x);
            } else if (expY > expX + 27) {
                // x is neglectible with respect to y
                return abs(y);
            } else {

                // Find an intermediate scale to avoid both overflow and underflow.
                // This involves shifting both x and y to a common exponent range around 0
                // before squaring and summing to prevent intermediate results from overflowing or underflowing.
                final int middleExp = (expX + expY) / 2;

                // Scale parameters without losing precision by adjusting their exponents
                final double scaledX = scalb(x, -middleExp);
                final double scaledY = scalb(y, -middleExp);

                // Compute scaled hypotenuse: sqrt(scaledX^2 + scaledY^2)
                final double scaledH = sqrt(scaledX * scaledX + scaledY * scaledY);

                // Remove scaling: multiply by 2^middleExp
                return scalb(scaledH, middleExp);
            }
        }
    }

    /**
     * Computes the remainder as prescribed by the IEEE 754 standard.
     * The remainder value is mathematically equal to {@code x - y*n}
     * where {@code n} is the mathematical integer closest to the exact mathematical value
     * of the quotient {@code x/y}.
     * If two mathematical integers are equally close to {@code x/y} then
     * {@code n} is the integer that is even.
     * <p>
     * <ul>
     * <li>If either operand is NaN, the result is NaN.</li>
     * <li>If the result is not NaN, the sign of the result equals the sign of the dividend.</li>
     * <li>If the dividend is an infinity, or the divisor is a zero, or both, the result is NaN.</li>
     * <li>If the dividend is finite and the divisor is an infinity, the result equals the dividend.</li>
     * <li>If the dividend is a zero and the divisor is finite, the result equals the dividend.</li>
     * </ul>
     * <p><b>Note:</b> this implementation currently delegates to {@link StrictMath#IEEEremainder}
     * @param dividend the number to be divided
     * @param divisor the number by which to divide
     * @return the remainder, rounded
     */
    public static double IEEEremainder(double dividend, double divisor) {
        return StrictMath.IEEEremainder(dividend, divisor); // TODO provide our own implementation
    }

    /**
     * Returns the first argument with the sign of the second argument.
     * A NaN {@code sign} argument is treated as positive.
     *
     * @param magnitude the value to return
     * @param sign the sign for the returned value
     * @return the magnitude with the same sign as the {@code sign} argument
     */
    public static double copySign(double magnitude, double sign){
        // The highest order bit of `magnitude` is the sign bit.
        // The highest order bit of `sign` determines the desired sign.
        // If they differ, flip the sign of magnitude.
        final long magnitudeBits = Double.doubleToRawLongBits(magnitude); // don't care about NaN bit pattern directly
        final long signBits = Double.doubleToRawLongBits(sign);

        // Extract sign bit of magnitude and desired sign bit from 'sign'
        final long magnitudeSignBit = magnitudeBits & 0x8000000000000000L;
        final long desiredSignBit = signBits & 0x8000000000000000L;

        if (magnitudeSignBit == desiredSignBit) {
            return magnitude; // Signs already match
        } else {
            // Signs differ, flip magnitude's sign.
            return Double.longBitsToDouble(magnitudeBits ^ 0x8000000000000000L);
        }
    }

    /**
     * Returns the first argument with the sign of the second argument.
     * A NaN {@code sign} argument is treated as positive.
     *
     * @param magnitude the value to return
     * @param sign the sign for the returned value
     * @return the magnitude with the same sign as the {@code sign} argument
     */
    public static float copySign(float magnitude, float sign){
        final int magnitudeBits = Float.floatToRawIntBits(magnitude);
        final int signBits = Float.floatToRawIntBits(sign);

        final int magnitudeSignBit = magnitudeBits & 0x80000000;
        final int desiredSignBit = signBits & 0x80000000;

        if (magnitudeSignBit == desiredSignBit) {
            return magnitude;
        } else {
            return Float.intBitsToFloat(magnitudeBits ^ 0x80000000);
        }
    }

    /**
     * Return the exponent of a double number, removing the bias.
     * <p>
     * For double numbers of the form 2<sup>x</sup>, the unbiased
     * exponent is exactly x.
     * </p>
     * @param d number from which exponent is requested
     * @return exponent for d in IEEE754 representation, without bias
     */
    public static int getExponent(final double d) {
        // NaN and Infinite will return 1024 anywho so can use raw bits
        return (int) ((Double.doubleToRawLongBits(d) >>> 52) & 0x7ff) - 1023;
    }

    /**
     * Return the exponent of a float number, removing the bias.
     * <p>
     * For float numbers of the form 2<sup>x</sup>, the unbiased
     * exponent is exactly x.
     * </p>
     * @param f number from which exponent is requested
     * @return exponent for d in IEEE754 representation, without bias
     */
    public static int getExponent(final float f) {
        // NaN and Infinite will return the same exponent anywho so can use raw bits
        return ((Float.floatToRawIntBits(f) >>> 23) & 0xff) - 127;
    }

    /**
     * Print out contents of arrays, and check the length.
     * <p>used to generate the preset arrays originally.</p>
     * @param a unused
     */
    public static void main(String[] a) {
        PrintStream out = System.out;
        FastMathCalc.printarray(out, "EXP_INT_TABLE_A", EXP_INT_TABLE_LEN, ExpIntTable.EXP_INT_TABLE_A);
        FastMathCalc.printarray(out, "EXP_INT_TABLE_B", EXP_INT_TABLE_LEN, ExpIntTable.EXP_INT_TABLE_B);
        FastMathCalc.printarray(out, "EXP_FRAC_TABLE_A", EXP_FRAC_TABLE_LEN, ExpFracTable.EXP_FRAC_TABLE_A);
        FastMathCalc.printarray(out, "EXP_FRAC_TABLE_B", EXP_FRAC_TABLE_LEN, ExpFracTable.EXP_FRAC_TABLE_B);
        FastMathCalc.printarray(out, "LN_MANT",LN_MANT_LEN, lnMant.LN_MANT);
        FastMathCalc.printarray(out, "SINE_TABLE_A", SINE_TABLE_LEN, SINE_TABLE_A);
        FastMathCalc.printarray(out, "SINE_TABLE_B", SINE_TABLE_LEN, SINE_TABLE_B);
        FastMathCalc.printarray(out, "COSINE_TABLE_A", SINE_TABLE_LEN, COSINE_TABLE_A);
        FastMathCalc.printarray(out, "COSINE_TABLE_B", SINE_TABLE_LEN, COSINE_TABLE_B);
        FastMathCalc.printarray(out, "TANGENT_TABLE_A", SINE_TABLE_LEN, TANGENT_TABLE_A);
        FastMathCalc.printarray(out, "TANGENT_TABLE_B", SINE_TABLE_LEN, TANGENT_TABLE_B);
    }

    /** Enclose large data table in nested static class so it's only loaded on first access. */
    private static class ExpIntTable {
        /** Exponential evaluated at integer values,
         * exp(x) =  expIntTableA[x + EXP_INT_TABLE_MAX_INDEX] + expIntTableB[x+EXP_INT_TABLE_MAX_INDEX].
         */
        private static final double[] EXP_INT_TABLE_A;
        /** Exponential evaluated at integer values,
         * exp(x) =  expIntTableA[x + EXP_INT_TABLE_MAX_INDEX] + expIntTableB[x+EXP_INT_TABLE_MAX_INDEX]
         */
        private static final double[] EXP_INT_TABLE_B;

        static {
            if (RECOMPUTE_TABLES_AT_RUNTIME) {
                EXP_INT_TABLE_A = new double[FastMath.EXP_INT_TABLE_LEN];
                EXP_INT_TABLE_B = new double[FastMath.EXP_INT_TABLE_LEN];

                final double tmp[] = new double[2];
                final double recip[] = new double[2];

                // Populate expIntTable
                for (int i = 0; i < FastMath.EXP_INT_TABLE_MAX_INDEX; i++) {
                    FastMathCalc.expint(i, tmp);
                    EXP_INT_TABLE_A[i + FastMath.EXP_INT_TABLE_MAX_INDEX] = tmp[0];
                    EXP_INT_TABLE_B[i + FastMath.EXP_INT_TABLE_MAX_INDEX] = tmp[1];

                    if (i != 0) {
                        // Negative integer powers
                        FastMathCalc.splitReciprocal(tmp, recip);
                        EXP_INT_TABLE_A[FastMath.EXP_INT_TABLE_MAX_INDEX - i] = recip[0];
                        EXP_INT_TABLE_B[FastMath.EXP_INT_TABLE_MAX_INDEX - i] = recip[1];
                    }
                }
            } else {
                EXP_INT_TABLE_A = FastMathLiteralArrays.loadExpIntA();
                EXP_INT_TABLE_B = FastMathLiteralArrays.loadExpIntB();
            }
        }
    }

    /** Enclose large data table in nested static class so it's only loaded on first access. */
    private static class ExpFracTable {
        /** Exponential over the range of 0 - 1 in increments of 2^-10
         * exp(x/1024) =  expFracTableA[x] + expFracTableB[x].
         * 1024 = 2^10
         */
        private static final double[] EXP_FRAC_TABLE_A;
        /** Exponential over the range of 0 - 1 in increments of 2^-10
         * exp(x/1024) =  expFracTableA[x] + expFracTableB[x].
         */
        private static final double[] EXP_FRAC_TABLE_B;

        static {
            if (RECOMPUTE_TABLES_AT_RUNTIME) {
                EXP_FRAC_TABLE_A = new double[FastMath.EXP_FRAC_TABLE_LEN];
                EXP_FRAC_TABLE_B = new double[FastMath.EXP_FRAC_TABLE_LEN];

                final double tmp[] = new double[2];

                // Populate expFracTable
                final double factor = 1d / (EXP_FRAC_TABLE_LEN - 1);
                for (int i = 0; i < EXP_FRAC_TABLE_A.length; i++) {
                    FastMathCalc.slowexp(i * factor, tmp);
                    EXP_FRAC_TABLE_A[i] = tmp[0];
                    EXP_FRAC_TABLE_B[i] = tmp[1];
                }
            } else {
                EXP_FRAC_TABLE_A = FastMathLiteralArrays.loadExpFracA();
                EXP_FRAC_TABLE_B = FastMathLiteralArrays.loadExpFracB();
            }
        }
    }

    /** Enclose large data table in nested static class so it's only loaded on first access. */
    private static class lnMant {
        /** Extended precision logarithm table over the range 1 - 2 in increments of 2^-10. */
        private static final double[][] LN_MANT;

        static {
            if (RECOMPUTE_TABLES_AT_RUNTIME) {
                LN_MANT = new double[FastMath.LN_MANT_LEN][];

                // Populate lnMant table
                for (int i = 0; i < LN_MANT.length; i++) {
                    final double d = Double.longBitsToDouble( (((long) i) << 42) | 0x3ff0000000000000L );
                    LN_MANT[i] = FastMathCalc.slowLog(d);
                }
            } else {
                LN_MANT = FastMathLiteralArrays.loadLnMant();
            }
        }
    }

    /** Enclose the Cody/Waite reduction (used in "sin", "cos" and "tan"). */
    private static class CodyWaite {
        /** k, the integer part of x / (PI/2) */
        private final int kValue;
        /** remA, the high part of x - k * (PI/2) */
        private final double remainderA;
        /** remB, the low part of x - k * (PI/2) */
        private final double remainderB;

        /**
         * Constructs a CodyWaite object to reduce the argument xa.
         * @param xa The argument to reduce.
         */
        CodyWaite(double xa) {
            // Estimate k = (int)(xa / (PI/2))
            int k = (int)(xa * 0.6366197723675814); // 2/PI approx

            double remHigh;
            double remLow;
            while (true) {
                // Compute remainder: xa - k * PI/2
                // PI/2 is split into 3 parts for precision.
                // pi/2 high part: 1.570796251296997
                // pi/2 mid part:  7.549789948768648E-8
                // pi/2 low part:  6.123233995736766E-17
                
                double term1 = -k * 1.570796251296997;
                remHigh = xa + term1;
                remLow = -(remHigh - xa - term1); // Error term

                double term2 = -k * 7.549789948768648E-8;
                double tempHigh = remHigh;
                remHigh = term2 + tempHigh;
                remLow += -(remHigh - tempHigh - term2);

                double term3 = -k * 6.123233995736766E-17;
                tempHigh = remHigh;
                remHigh = term3 + tempHigh;
                remLow += -(remHigh - tempHigh - term3);

                if (remHigh > 0) {
                    break;
                }

                // Remainder is negative, so decrement k and try again.
                // This should only happen if the input is very close
                // to an even multiple of pi/2 (due to precision issues).
                --k;
            }

            this.kValue = k;
            this.remainderA = remHigh;
            this.remainderB = remLow;
        }

        /**
         * @return k, the integer part of the argument divided by PI/2
         */
        int getK() {
            return kValue;
        }
        /**
         * @return remA, the high part of the remainder
         */
        double getRemA() {
            return remainderA;
        }
        /**
         * @return remB, the low part of the remainder
         */
        double getRemB() {
            return remainderB;
        }
    }
}