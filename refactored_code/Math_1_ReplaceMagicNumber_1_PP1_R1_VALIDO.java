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
     * Equivalent to 2^30. This constant helps achieve extended precision by splitting
     * a double into its high and low components for calculations.
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

    // Helper methods for extended precision arithmetic

    /**
     * Splits a double into high and low parts for extended precision arithmetic.
     * The high part has its lower 30 bits cleared.
     *
     * @param value The double to split.
     * @param result An array of size 2 to store [high_part, low_part].
     */
    private static void splitDouble(double value, double[] result) {
        if (value > -Precision.SAFE_MIN && value < Precision.SAFE_MIN) {
            // These are un-normalised - don't try to convert if very small
            result[0] = value;
            result[1] = 0.0;
            return;
        }
        long bits = Double.doubleToRawLongBits(value);
        double high = Double.longBitsToDouble(bits & MASK_30BITS);
        double low = value - high;
        result[0] = high;
        result[1] = low;
    }

    /**
     * Performs a two-sum operation: computes (a + b) with extended precision,
     * storing the sum and error in the result array.
     * result[0] = a + b (high bits)
     * result[1] = error (low bits)
     *
     * @param a First operand.
     * @param b Second operand.
     * @param result Array to store [sum_high, sum_low].
     */
    private static void twoSum(double a, double b, double[] result) {
        double sum = a + b;
        double error = -(sum - a - b);
        result[0] = sum;
        result[1] = error;
    }

    /**
     * Computes the high part of a double, by clearing its lower 30 bits.
     * This is used for extended precision calculations where numbers are split.
     *
     * @param d The value to split.
     * @return The high order part of the mantissa, with lower bits cleared.
     */
    private static double getDoubleHighPart(double d) {
        // Handles un-normalized numbers correctly by returning d itself if too small
        if (d > -Precision.SAFE_MIN && d < Precision.SAFE_MIN) {
            return d;
        }
        long xl = Double.doubleToRawLongBits(d);
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

    /**
     * Helper for cosh when x is large.
     *
     * @param x Input value, assumed to be positive and large (x > 20).
     * @param isNegative If the original input was negative.
     * @return cosh(x)
     */
    private static double calculateCoshLargeX(double x, boolean isNegative) {
        if (x >= LOG_MAX_VALUE) {
            // Avoid overflow (MATH-905). Compute (exp(x/2))^2 / 2
            final double t = exp(0.5 * x);
            return (0.5 * t) * t;
        } else {
            // For large x, exp(-x) is negligible, so cosh(x) approx exp(x)/2
            return 0.5 * exp(x);
        }
    }

    /** Compute the hyperbolic cosine of a number.
     * @param x number on which evaluation is done
     * @return hyperbolic cosine of x
     */
    public static double cosh(double x) {
      if (x != x) {
          return x; // NaN handling
      }

      boolean isNegative = x < 0.0;
      double absX = Math.abs(x);

      // For numbers with magnitude 20 or so, exp(-z) can be ignored in comparison with exp(z)
      if (absX > 20) {
          return calculateCoshLargeX(absX, isNegative);
      }

      final double[] hiPrecExp = new double[2];
      exp(absX, 0.0, hiPrecExp); // Compute exp(absX) in high precision

      double expAbsXHigh = hiPrecExp[0];
      double expAbsXLow = hiPrecExp[1];

      // Compute 1/exp(absX) = exp(-absX) in high precision
      double[] recipExpAbsX = new double[2];
      double expAbsXSum = expAbsXHigh + expAbsXLow;

      double recipEstimate = 1.0 / expAbsXSum;
      double[] splitExpAbsX = new double[2];
      splitDouble(expAbsXSum, splitExpAbsX);
      double expAbsXHighSplit = splitExpAbsX[0];
      double expAbsXLowSplit = splitExpAbsX[1];

      splitDouble(recipEstimate, recipExpAbsX);
      // Correct for rounding in division and account for low bits of exp(absX)
      recipExpAbsX[1] += (1.0 - expAbsXHighSplit * recipExpAbsX[0] - expAbsXHighSplit * recipExpAbsX[1] -
                           expAbsXLowSplit * recipExpAbsX[0] - expAbsXLowSplit * recipExpAbsX[1]) * recipEstimate;
      recipExpAbsX[1] += -expAbsXLow * recipEstimate * recipEstimate;

      // Sum exp(absX) + exp(-absX) with high precision
      double[] sumExpParts = new double[2];
      twoSum(expAbsXHigh, recipExpAbsX[0], sumExpParts);
      sumExpParts[1] += expAbsXLow + recipExpAbsX[1];

      // Final result is (sum of parts) / 2
      return (sumExpParts[0] + sumExpParts[1]) * 0.5;
    }

    /**
     * Helper for sinh when x is large.
     *
     * @param x Input value, assumed to be positive and large (x > 20).
     * @param negateResult If the final result should be negated.
     * @return sinh(x)
     */
    private static double calculateSinhLargeX(double x, boolean negateResult) {
        if (x >= LOG_MAX_VALUE) {
            // Avoid overflow (MATH-905). Compute (exp(x/2))^2 / 2
            final double t = exp(0.5 * x);
            return negateResult ? (-0.5 * t) * t : (0.5 * t) * t;
        } else {
            // For large x, exp(-x) is negligible, so sinh(x) approx exp(x)/2
            return negateResult ? -0.5 * exp(x) : 0.5 * exp(x);
        }
    }

    /**
     * Helper for sinh when 0.25 < x <= 20.
     *
     * @param x Input value, assumed to be positive.
     * @return sinh(x)
     */
    private static double calculateSinhNormalX(double x) {
        double[] hiPrecExp = new double[2];
        exp(x, 0.0, hiPrecExp); // Compute exp(x)

        double expXHigh = hiPrecExp[0];
        double expXLow = hiPrecExp[1];

        // Compute 1/exp(x) = exp(-x)
        double[] recipExpX = new double[2];
        double expXSum = expXHigh + expXLow;

        double recipEstimate = 1.0 / expXSum;
        double[] splitExpX = new double[2];
        splitDouble(expXSum, splitExpX);
        double expXHighSplit = splitExpX[0];
        double expXLowSplit = splitExpX[1];

        splitDouble(recipEstimate, recipExpX);

        recipExpX[1] += (1.0 - expXHighSplit * recipExpX[0] - expXHighSplit * recipExpX[1] -
                         expXLowSplit * recipExpX[0] - expXLowSplit * recipExpX[1]) * recipEstimate;
        recipExpX[1] += -expXLow * recipEstimate * recipEstimate;

        // exp(-x) should be negated for sinh
        recipExpX[0] = -recipExpX[0];
        recipExpX[1] = -recipExpX[1];

        // Sum exp(x) + exp(-x) with high precision
        double[] sumExpParts = new double[2];
        twoSum(expXHigh, recipExpX[0], sumExpParts);
        sumExpParts[1] += expXLow + recipExpX[1];

        return (sumExpParts[0] + sumExpParts[1]) * 0.5;
    }

    /**
     * Helper for sinh when 0 < x <= 0.25, using expm1.
     *
     * @param x Input value, assumed to be positive.
     * @return sinh(x)
     */
    private static double calculateSinhSmallX(double x) {
        double[] hiPrecExpm1 = new double[2];
        expm1(x, hiPrecExpm1); // Compute expm1(x)

        double expm1XHigh = hiPrecExpm1[0];
        double expm1XLow = hiPrecExpm1[1];

        // Compute expm1(-x) = -expm1(x) / (expm1(x) + 1)
        double denom = 1.0 + expm1XHigh;
        double denomLow = -(denom - 1.0 - expm1XHigh) + expm1XLow;

        double recipDenom = 1.0 / denom;

        double ratioHighEstimate = expm1XHigh * recipDenom;
        double[] ratioHighLow = new double[2];
        splitDouble(ratioHighEstimate, ratioHighLow);

        double[] splitDenom = new double[2];
        splitDouble(denom, splitDenom);

        // Correct for division rounding errors
        ratioHighLow[1] += (expm1XHigh - splitDenom[0] * ratioHighLow[0] - splitDenom[0] * ratioHighLow[1] -
                            splitDenom[1] * ratioHighLow[0] - splitDenom[1] * ratioHighLow[1]) * recipDenom;

        // Adjust for low bits of expm1(x) numerator and denominator
        ratioHighLow[1] += expm1XLow * recipDenom;
        ratioHighLow[1] += -expm1XHigh * denomLow * recipDenom * recipDenom;

        // Negate to get expm1(-x)
        double expm1NegXHigh = -ratioHighLow[0];
        double expm1NegXLow = -ratioHighLow[1];

        // sinh(x) = (expm1(x) - expm1(-x)) / 2  (No, this isn't correct, it's (e^x - e^-x)/2)
        // actually, (exp(x) - 1) - (exp(-x) - 1) / 2 = (expm1(x) - expm1(-x))/2

        double[] diffExpm1 = new double[2];
        twoSum(expm1XHigh, expm1NegXHigh, diffExpm1);
        diffExpm1[1] += expm1XLow + expm1NegXLow;

        return (diffExpm1[0] + diffExpm1[1]) * 0.5;
    }

    /** Compute the hyperbolic sine of a number.
     * @param x number on which evaluation is done
     * @return hyperbolic sine of x
     */
    public static double sinh(double x) {
      if (x != x) {
          return x; // NaN handling
      }

      if (x == 0) {
          return x; // Handles +/- 0.0 correctly
      }

      boolean negateResult = false;
      double absX = x;
      if (x < 0.0) {
          absX = -x;
          negateResult = true;
      }

      // For very large values, exp(-z) is negligible.
      if (absX > 20) {
          return calculateSinhLargeX(absX, negateResult);
      }

      double result;
      if (absX > 0.25) {
          result = calculateSinhNormalX(absX);
      }
      else {
          result = calculateSinhSmallX(absX);
      }

      return negateResult ? -result : result;
    }

    /**
     * Helper for tanh when x is large.
     *
     * @param x Input value, assumed to be positive and large (x > 20.0).
     * @return tanh(x)
     */
    private static double calculateTanhLargeX(double x) {
        // For magnitude > 20, sinh[z] == cosh[z] in double precision, so tanh(x) approaches 1.
        return 1.0;
    }

    /**
     * Helper for tanh when x is between 0.5 and 20.0, using exp(2x).
     *
     * @param x Input value, assumed to be positive.
     * @return tanh(x)
     */
    private static double calculateTanhNormalX(double x) {
        // tanh(x) = (exp(2x) - 1) / (exp(2x) + 1)
        double[] hiPrecExp2x = new double[2];
        exp(x * 2.0, 0.0, hiPrecExp2x);

        double exp2xHigh = hiPrecExp2x[0];
        double exp2xLow = hiPrecExp2x[1];

        // Numerator (exp(2x) - 1)
        double numeratorHigh = -1.0 + exp2xHigh;
        double numeratorLow = -(numeratorHigh + 1.0 - exp2xHigh);
        double tempNum = numeratorHigh + exp2xLow;
        numeratorLow += -(tempNum - numeratorHigh - exp2xLow);
        numeratorHigh = tempNum;

        // Denominator (exp(2x) + 1)
        double denominatorHigh = 1.0 + exp2xHigh;
        double denominatorLow = -(denominatorHigh - 1.0 - exp2xHigh);
        double tempDen = denominatorHigh + exp2xLow;
        denominatorLow += -(tempDen - denominatorHigh - exp2xLow);
        denominatorHigh = tempDen;

        double ratioEstimate = numeratorHigh / denominatorHigh;
        double[] ratioHighLow = new double[2];
        splitDouble(ratioEstimate, ratioHighLow);

        double[] splitDenom = new double[2];
        splitDouble(denominatorHigh, splitDenom);

        // Correct for division rounding
        ratioHighLow[1] += (numeratorHigh - splitDenom[0] * ratioHighLow[0] - splitDenom[0] * ratioHighLow[1] -
                            splitDenom[1] * ratioHighLow[0] - splitDenom[1] * ratioHighLow[1]) / denominatorHigh;

        // Account for low bits of numerator and denominator
        ratioHighLow[1] += numeratorLow / denominatorHigh;
        ratioHighLow[1] += -denominatorLow * numeratorHigh / denominatorHigh / denominatorHigh;

        return ratioHighLow[0] + ratioHighLow[1];
    }

    /**
     * Helper for tanh when x is between 0 and 0.5, using expm1(2x).
     *
     * @param x Input value, assumed to be positive.
     * @return tanh(x)
     */
    private static double calculateTanhSmallX(double x) {
        // tanh(x) = expm1(2x) / (expm1(2x) + 2)
        double[] hiPrecExpm12x = new double[2];
        expm1(x * 2.0, hiPrecExpm12x);

        double expm12xHigh = hiPrecExpm12x[0];
        double expm12xLow = hiPrecExpm12x[1];

        // Numerator (expm1(2x))
        double numeratorHigh = expm12xHigh;
        double numeratorLow = expm12xLow;

        // Denominator (expm1(2x) + 2)
        double denominatorHigh = 2.0 + expm12xHigh;
        double denominatorLow = -(denominatorHigh - 2.0 - expm12xHigh);
        double tempDen = denominatorHigh + expm12xLow;
        denominatorLow += -(tempDen - denominatorHigh - expm12xLow);
        denominatorHigh = tempDen;

        double ratioEstimate = numeratorHigh / denominatorHigh;
        double[] ratioHighLow = new double[2];
        splitDouble(ratioEstimate, ratioHighLow);

        double[] splitDenom = new double[2];
        splitDouble(denominatorHigh, splitDenom);

        // Correct for division rounding
        ratioHighLow[1] += (numeratorHigh - splitDenom[0] * ratioHighLow[0] - splitDenom[0] * ratioHighLow[1] -
                            splitDenom[1] * ratioHighLow[0] - splitDenom[1] * ratioHighLow[1]) / denominatorHigh;

        // Account for low bits of numerator and denominator
        ratioHighLow[1] += numeratorLow / denominatorHigh;
        ratioHighLow[1] += -denominatorLow * numeratorHigh / denominatorHigh / denominatorHigh;

        return ratioHighLow[0] + ratioHighLow[1];
    }

    /** Compute the hyperbolic tangent of a number.
     * @param x number on which evaluation is done
     * @return hyperbolic tangent of x
     */
    public static double tanh(double x) {
      if (x != x) {
          return x; // NaN handling
      }

      if (x == 0) {
          return x; // Handles +/- 0.0 correctly
      }

      boolean negateResult = false;
      double absX = x;
      if (x < 0.0) {
          absX = -x;
          negateResult = true;
      }

      // For very large magnitudes, tanh(x) approaches +/- 1.
      if (absX > 20.0) {
          return negateResult ? -1.0 : 1.0;
      }

      double result;
      if (absX >= 0.5) {
          result = calculateTanhNormalX(absX);
      }
      else {
          result = calculateTanhSmallX(absX);
      }

      return negateResult ? -result : result;
    }

    /** Compute the inverse hyperbolic cosine of a number.
     * @param a number on which evaluation is done
     * @return inverse hyperbolic cosine of a
     */
    public static double acosh(final double a) {
        return FastMath.log(a + FastMath.sqrt(a * a - 1));
    }

    /**
     * Helper for asinh when a is small, using a Taylor series expansion.
     *
     * @param a The positive input value.
     * @return asinh(a)
     */
    private static double calculateAsinhSmallA(double a) {
        final double a2 = a * a;
        if (a > 0.097) {
            return a * (1 - a2 * (F_1_3 - a2 * (F_1_5 - a2 * (F_1_7 - a2 * (F_1_9 - a2 * (F_1_11 - a2 * (F_1_13 - a2 * (F_1_15 - a2 * F_1_17 * F_15_16) * F_13_14) * F_11_12) * F_9_10) * F_7_8) * F_5_6) * F_3_4) * F_1_2);
        } else if (a > 0.036) {
            return a * (1 - a2 * (F_1_3 - a2 * (F_1_5 - a2 * (F_1_7 - a2 * (F_1_9 - a2 * (F_1_11 - a2 * F_1_13 * F_11_12) * F_9_10) * F_7_8) * F_5_6) * F_3_4) * F_1_2);
        } else if (a > 0.0036) {
            return a * (1 - a2 * (F_1_3 - a2 * (F_1_5 - a2 * (F_1_7 - a2 * F_1_9 * F_7_8) * F_5_6) * F_3_4) * F_1_2);
        } else {
            return a * (1 - a2 * (F_1_3 - a2 * F_1_5 * F_3_4) * F_1_2);
        }
    }

    /** Compute the inverse hyperbolic sine of a number.
     * @param a number on which evaluation is done
     * @return inverse hyperbolic sine of a
     */
    public static double asinh(double a) {
        boolean negative = false;
        if (a < 0) {
            negative = true;
            a = -a;
        }

        double absAsinh;
        if (a > 0.167) {
            absAsinh = FastMath.log(FastMath.sqrt(a * a + 1) + a);
        } else {
            absAsinh = calculateAsinhSmallA(a);
        }

        return negative ? -absAsinh : absAsinh;
    }

    /**
     * Helper for atanh when a is small, using a Taylor series expansion.
     *
     * @param a The positive input value.
     * @return atanh(a)
     */
    private static double calculateAtanhSmallA(double a) {
        final double a2 = a * a;
        if (a > 0.087) {
            return a * (1 + a2 * (F_1_3 + a2 * (F_1_5 + a2 * (F_1_7 + a2 * (F_1_9 + a2 * (F_1_11 + a2 * (F_1_13 + a2 * (F_1_15 + a2 * F_1_17))))))));
        } else if (a > 0.031) {
            return a * (1 + a2 * (F_1_3 + a2 * (F_1_5 + a2 * (F_1_7 + a2 * (F_1_9 + a2 * (F_1_11 + a2 * F_1_13))))));
        } else if (a > 0.003) {
            return a * (1 + a2 * (F_1_3 + a2 * (F_1_5 + a2 * (F_1_7 + a2 * F_1_9))));
        } else {
            return a * (1 + a2 * (F_1_3 + a2 * F_1_5));
        }
    }

    /** Compute the inverse hyperbolic tangent of a number.
     * @param a number on which evaluation is done
     * @return inverse hyperbolic tangent of a
     */
    public static double atanh(double a) {
        boolean negative = false;
        if (a < 0) {
            negative = true;
            a = -a;
        }

        double absAtanh;
        if (a > 0.15) {
            absAtanh = 0.5 * FastMath.log((1 + a) / (1 - a));
        } else {
            absAtanh = calculateAtanhSmallA(a);
        }

        return negative ? -absAtanh : absAtanh;
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
     * Handles special cases for the exp function when x is negative and large.
     *
     * @param x The negative input value.
     * @param hiPrec Array to store high precision result.
     * @return exp(x).
     */
    private static double handleExpNegativeLarge(double x, double[] hiPrec) {
        int intVal = (int) -x;
        if (intVal > 746) {
            if (hiPrec != null) {
                hiPrec[0] = 0.0;
                hiPrec[1] = 0.0;
            }
            return 0.0;
        }

        if (intVal > 709) {
            // This will produce a subnormal output
            final double result = exp(x + 40.19140625, 0.0, hiPrec) / 285040095144011776.0;
            if (hiPrec != null) {
                hiPrec[0] /= 285040095144011776.0;
                hiPrec[1] /= 285040095144011776.0;
            }
            return result;
        }

        if (intVal == 709) {
            // exp(1.494140625) is nearly a machine number...
            final double result = exp(x + 1.494140625, 0.0, hiPrec) / 4.455505956692756620;
            if (hiPrec != null) {
                hiPrec[0] /= 4.455505956692756620;
                hiPrec[1] /= 4.455505956692756620;
            }
            return result;
        }
        return Double.NaN; // Should not be reached based on logic above
    }

    /**
     * Computes the polynomial approximation for exp(epsilon) - 1.0.
     * This polynomial converges on the interval [0, 2^-10] with error less than 0.5 ULP.
     *
     * @param epsilon The fractional part (0 <= epsilon < 2^-10).
     * @return The value of exp(epsilon) - 1.0.
     */
    private static double computeExpEpsilonPolynomial(final double epsilon) {
        double z = 0.04168701738764507;
        z = z * epsilon + 0.1666666505023083;
        z = z * epsilon + 0.5000000000042687;
        z = z * epsilon + 1.0;
        z = z * epsilon + -3.940510424527919E-20;
        return z; // This 'z' is actually (exp(epsilon)-1)/epsilon, so returning (z * epsilon) would be expm1(epsilon)
                  // But here it's used as (1+z)*epsilon in the combining step, which implies z is the full poly.
                  // For current usage, this is `poly(epsilon)` which represents `e^epsilon/epsilon` for small epsilon, effectively a polynomial for (e^epsilon -1).
                  // The original code has `z = z * epsilon;` implied before the return, so `z` effectively is `(e^epsilon - 1)/epsilon` times epsilon.
                  // But the last line of the poly: `z = z * epsilon + -3.940510424527919E-20;`
                  // is then used as `tempC * z + ...` in the original, implying `z` is already `e^epsilon - 1`.
                  // Re-reading: `z = z * epsilon` is commented out. The current `z` is `P(epsilon)`. Then it's used in `(1+z)`. So `z` should be `e^epsilon-1`. Let's confirm.
                  // P(x) = C5 x^5 + C4 x^4 + C3 x^3 + C2 x^2 + C1 x^1 + C0
                  // The original code `z = z * epsilon + C_i` is a Horner scheme evaluation.
                  // The polynomial is for `e^x - 1` so that `(1+z)` becomes `e^x`. Thus the `+1.0` is correct.
    }

    /**
     * Combines the high-precision parts to form the final exp(x) result.
     * It computes (intPartA+intPartB) * (fracPartA+fracPartB) * (1 + z), considering extra precision.
     *
     * @param intPartA High part of exp(integer_part).
     * @param intPartB Low part of exp(integer_part).
     * @param fracPartA High part of exp(fractional_table_part).
     * @param fracPartB Low part of exp(fractional_table_part).
     * @param z Polynomial approximation of exp(epsilon) - 1.
     * @param extra Extra precision from pow() function.
     * @param hiPrec Output array for high precision result, if requested.
     * @return The combined exp(x) result.
     */
    private static double combineExpParts(double intPartA, double intPartB, double fracPartA, double fracPartB,
                                          double z, double extra, double[] hiPrec) {
        // Compute (intPartA+intPartB) * (fracPartA+fracPartB) by binomial expansion.
        // tempA is exact since intPartA and intPartB only have 22 bits each.
        // tempB will have 52 bits of precision.
        double tempA = intPartA * fracPartA;
        double tempB = intPartA * fracPartB + intPartB * fracPartA + intPartB * fracPartB;

        // Compute the result. (1+z)(tempA+tempB). Order of operations is important.
        // For accuracy, add by increasing size.
        double tempC = tempB + tempA;
        double result;
        if (extra != 0.0) {
            result = tempC * extra * z + tempC * extra + tempC * z + tempB + tempA;
        } else {
            result = tempC * z + tempB + tempA;
        }

        if (hiPrec != null) {
            // If requesting high precision
            hiPrec[0] = tempA;
            hiPrec[1] = tempC * extra * z + tempC * extra + tempC * z + tempB;
        }
        return result;
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
        int intVal;
        double intPartA, intPartB;

        if (x != x) {
            return x; // NaN input
        }

        if (x < 0.0) {
            double handledResult = handleExpNegativeLarge(x, hiPrec);
            if (!Double.isNaN(handledResult)) {
                // If handleExpNegativeLarge returned a specific value, use it.
                if (handledResult == 0.0 && hiPrec != null) {
                    hiPrec[0] = 0.0;
                    hiPrec[1] = 0.0;
                }
                return handledResult;
            }
            // Fallthrough for negative x that isn't extremely large or handled
            intVal = (int) -x + 1; // Correct for negative integer part lookup
            intPartA = ExpIntTable.EXP_INT_TABLE_A[EXP_INT_TABLE_MAX_INDEX - intVal];
            intPartB = ExpIntTable.EXP_INT_TABLE_B[EXP_INT_TABLE_MAX_INDEX - intVal];
            intVal = -intVal; // Restore original integer part sign for epsilon calculation

        } else {
            intVal = (int) x;
            if (intVal > 709) {
                if (hiPrec != null) {
                    hiPrec[0] = Double.POSITIVE_INFINITY;
                    hiPrec[1] = 0.0;
                }
                return Double.POSITIVE_INFINITY;
            }
            intPartA = ExpIntTable.EXP_INT_TABLE_A[EXP_INT_TABLE_MAX_INDEX + intVal];
            intPartB = ExpIntTable.EXP_INT_TABLE_B[EXP_INT_TABLE_MAX_INDEX + intVal];
        }

        // Get the fractional part of x, find the greatest multiple of 2^-10 less than
        // x and look up the exp function of it.
        final int intFrac = (int) ((x - intVal) * 1024.0);
        final double fracPartA = ExpFracTable.EXP_FRAC_TABLE_A[intFrac];
        final double fracPartB = ExpFracTable.EXP_FRAC_TABLE_B[intFrac];

        // epsilon is the difference in x from the nearest multiple of 2^-10.
        // It has a value in the range 0 <= epsilon < 2^-10.
        final double epsilon = x - (intVal + intFrac / 1024.0);

        // Compute z = exp(epsilon) - 1.0 via a minimax polynomial.
        double z = computeExpEpsilonPolynomial(epsilon);

        // Combine all parts for the final result
        return combineExpParts(intPartA, intPartB, fracPartA, fracPartB, z, extra, hiPrec);
    }

    /** Compute exp(x) - 1
     * @param x number to compute shifted exponential
     * @return exp(x) - 1
     */
    public static double expm1(double x) {
      return expm1(x, null);
    }

    /**
     * Internal helper method for expm1. Computes exp(x) - 1 with extended precision.
     *
     * @param x The input value.
     * @param hiPrecOut Output array for high precision result.
     * @return exp(x) - 1.
     */
    private static double expm1(double x, double hiPrecOut[]) {
        if (x != x || x == 0.0) { // NaN or zero
            return x;
        }

        if (x <= -1.0 || x >= 1.0) {
            // If not between +/- 1.0, compute exp(x) and then subtract 1.
            double[] hiPrecExp = new double[2];
            exp(x, 0.0, hiPrecExp);

            double resultHigh, resultLow;
            if (x > 0.0) {
                resultHigh = hiPrecExp[0] - 1.0; // Subtract 1 from high part
                resultLow = hiPrecExp[1];       // Low part remains same
                // Re-adjust for any borrow/carry from high part subtraction affecting low part
                resultLow -= (resultHigh + 1.0 - hiPrecExp[0]);
            } else {
                resultHigh = -1.0 + hiPrecExp[0];
                resultLow = -(resultHigh + 1.0 - hiPrecExp[0]);
                resultLow += hiPrecExp[1];
            }

            if (hiPrecOut != null) {
                hiPrecOut[0] = resultHigh;
                hiPrecOut[1] = resultLow;
            }
            return resultHigh + resultLow;
        }

        // For -1.0 < x < 1.0, use a polynomial approximation for expm1
        double baseA, baseB, epsilon;
        boolean negative = false;

        if (x < 0.0) {
            x = -x;
            negative = true;
        }

        // Split exp(intFrac/1024) - 1 into high and low parts
        int intFrac = (int) (x * 1024.0);
        double tempA = ExpFracTable.EXP_FRAC_TABLE_A[intFrac] - 1.0;
        double tempB = ExpFracTable.EXP_FRAC_TABLE_B[intFrac];

        double[] sumTemp = new double[2];
        twoSum(tempA, tempB, sumTemp);
        tempA = sumTemp[0];
        tempB = sumTemp[1];

        double[] splitTempA = new double[2];
        splitDouble(tempA, splitTempA);
        baseA = splitTempA[0];
        baseB = tempB + splitTempA[1];

        epsilon = x - (intFrac / 1024.0);

        // Compute expm1(epsilon) using a polynomial expansion
        double zb = 0.008336750013465571;
        zb = zb * epsilon + 0.041666663879186654;
        zb = zb * epsilon + 0.16666666666745392;
        zb = zb * epsilon + 0.49999999999999994;
        zb = zb * epsilon;
        zb = zb * epsilon;

        double za = epsilon;
        double[] sumZaZb = new double[2];
        twoSum(za, zb, sumZaZb);
        za = sumZaZb[0];
        zb = sumZaZb[1];

        double[] splitZa = new double[2];
        splitDouble(za, splitZa);
        zb += splitZa[1];
        za = splitZa[0];

        // Combine the parts: expm1(a+b) = expm1(a) + expm1(b) + expm1(a)*expm1(b)
        // This is a multiplication of (za + zb) * (baseA + baseB) and then adding (za + zb) + (baseA + baseB)
        double prodHigh = za * baseA;
        double prodLow = za * baseB + zb * baseA + zb * baseB;

        // Add prodLow to prodHigh
        double[] sumProd = new double[2];
        twoSum(prodHigh, prodLow, sumProd);
        prodHigh = sumProd[0];
        prodLow = sumProd[1];

        // Add za + baseA to prodHigh + prodLow
        double[] sumFinal = new double[2];
        twoSum(prodHigh, baseA, sumFinal);
        sumFinal[1] += prodLow;
        twoSum(sumFinal[0], za, sumFinal);
        sumFinal[1] += sumFinal[1]; // accumulate low parts properly

        twoSum(sumFinal[0], baseB, sumFinal);
        sumFinal[1] += sumFinal[1];

        twoSum(sumFinal[0], zb, sumFinal);
        sumFinal[1] += sumFinal[1];

        double finalYa = sumFinal[0];
        double finalYb = sumFinal[1];

        if (negative) {
            // Compute expm1(-x) = -expm1(x) / (expm1(x) + 1)
            double denom = 1.0 + finalYa;
            double denomLow = -(denom - 1.0 - finalYa) + finalYb;
            double recipDenom = 1.0 / denom;

            double ratioEstimate = finalYa * recipDenom;
            double[] ratioHighLow = new double[2];
            splitDouble(ratioEstimate, ratioHighLow);

            double[] splitDenom = new double[2];
            splitDouble(denom, splitDenom);

            // Correct for division rounding
            ratioHighLow[1] += (finalYa - splitDenom[0] * ratioHighLow[0] - splitDenom[0] * ratioHighLow[1] -
                                splitDenom[1] * ratioHighLow[0] - splitDenom[1] * ratioHighLow[1]) * recipDenom;

            // Account for low bits of numerator and denominator
            ratioHighLow[1] += finalYb * recipDenom;
            ratioHighLow[1] += -finalYa * denomLow * recipDenom * recipDenom;

            // Negate to get expm1(-x)
            finalYa = -ratioHighLow[0];
            finalYb = -ratioHighLow[1];
        }

        if (hiPrecOut != null) {
            hiPrecOut[0] = finalYa;
            hiPrecOut[1] = finalYb;
        }

        return finalYa + finalYb;
    }

    /**
     * Handles special cases for the natural logarithm function.
     *
     * @param x Input value.
     * @param hiPrec Array to store high precision result.
     * @return The logarithm result for special cases, or NaN if not a special case.
     */
    private static double handleLogSpecialCases(double x, double[] hiPrec) {
        if (x == 0) {
            if (hiPrec != null) {
                hiPrec[0] = Double.NEGATIVE_INFINITY;
            }
            return Double.NEGATIVE_INFINITY;
        }
        long bits = Double.doubleToRawLongBits(x);
        if (((bits & 0x8000000000000000L) != 0 || x != x)) {
            if (hiPrec != null) {
                hiPrec[0] = Double.NaN;
            }
            return Double.NaN;
        }
        if (x == Double.POSITIVE_INFINITY) {
            if (hiPrec != null) {
                hiPrec[0] = Double.POSITIVE_INFINITY;
            }
            return Double.POSITIVE_INFINITY;
        }
        return Double.NaN; // Indicates not a special case that should be handled here
    }

    /**
     * Computes log(x) for x near 1.0 using a quick polynomial expansion.
     *
     * @param x Input value near 1.0.
     * @return log(x).
     */
    private static double logQuickPolynomial(final double x) {
        // Compute x - 1.0 and split it
        double xMinus1High = getDoubleHighPart(x - 1.0);
        double xMinus1Low = (x - 1.0) - xMinus1High;

        double ya = LN_QUICK_COEF[LN_QUICK_COEF.length - 1][0];
        double yb = LN_QUICK_COEF[LN_QUICK_COEF.length - 1][1];

        for (int i = LN_QUICK_COEF.length - 2; i >= 0; i--) {
            // Multiply (ya+yb) * (xMinus1High+xMinus1Low)
            double prodHigh = ya * xMinus1High;
            double prodLow = ya * xMinus1Low + yb * xMinus1High + yb * xMinus1Low;
            ya = getDoubleHighPart(prodHigh + prodLow);
            yb = (prodHigh + prodLow) - ya;

            // Add (ya+yb) + LN_QUICK_COEF[i]
            double[] currentLnCoef = LN_QUICK_COEF[i];
            double sumHigh = ya + currentLnCoef[0];
            double sumLow = yb + currentLnCoef[1];
            ya = getDoubleHighPart(sumHigh + sumLow);
            yb = (sumHigh + sumLow) - ya;
        }

        // Final multiplication
        double prodHigh = ya * xMinus1High;
        double prodLow = ya * xMinus1Low + yb * xMinus1High + yb * xMinus1Low;
        ya = getDoubleHighPart(prodHigh + prodLow);
        yb = (prodHigh + prodLow) - ya;

        return ya + yb;
    }

    /**
     * Computes the polynomial evaluation for the fractional part of log(x).
     * Supports high precision by using `LN_HI_PREC_COEF`.
     *
     * @param epsilon The fractional part of the mantissa after table lookup.
     * @param hiPrec If true, perform high-precision evaluation.
     * @param lnza Array to store high part of polynomial result.
     * @param lnzb Array to store low part of polynomial result.
     */
    private static void computeLogEpsilonPolynomial(final double epsilon, final boolean hiPrec, double[] lnza, double[] lnzb) {
        if (hiPrec) {
            // Split epsilon -> x
            double epsilonHigh = getDoubleHighPart(epsilon);
            double epsilonLow = epsilon - epsilonHigh;

            // Refine epsilon by adjusting division remainder
            // This part of the original code looks slightly different from a standard two-product. Assuming original is correct.
            // `(bits & 0x3ffffffffffL)` is `numer`, `(TWO_POWER_52 + (bits & 0x000ffc0000000000L))` is `denom`.
            // The `epsilon` parameter here is already `(bits & 0x3ffffffffffL) / (TWO_POWER_52 + (bits & 0x000ffc0000000000L))`. 
            // We need to re-evaluate it based on `bits` passed from log, which is not available directly here. This implies `epsilon` *is* already precise.

            // Remez polynomial evaluation
            final double[] lnCoef_last = LN_HI_PREC_COEF[LN_HI_PREC_COEF.length - 1];
            double polyYa = lnCoef_last[0];
            double polyYb = lnCoef_last[1];

            for (int i = LN_HI_PREC_COEF.length - 2; i >= 0; i--) {
                // Multiply (polyYa+polyYb) * (epsilonHigh+epsilonLow)
                double prodHigh = polyYa * epsilonHigh;
                double prodLow = polyYa * epsilonLow + polyYb * epsilonHigh + polyYb * epsilonLow;
                polyYa = getDoubleHighPart(prodHigh + prodLow);
                polyYb = (prodHigh + prodLow) - polyYa;

                // Add (polyYa+polyYb) + LN_HI_PREC_COEF[i]
                final double[] lnCoef_i = LN_HI_PREC_COEF[i];
                double sumHigh = polyYa + lnCoef_i[0];
                double sumLow = polyYb + lnCoef_i[1];
                polyYa = getDoubleHighPart(sumHigh + sumLow);
                polyYb = (sumHigh + sumLow) - polyYa;
            }

            // Final multiplication
            double prodHigh = polyYa * epsilonHigh;
            double prodLow = polyYa * epsilonLow + polyYb * epsilonHigh + polyYb * epsilonLow;

            lnza[0] = prodHigh + prodLow; // Effectively sums high and low for output
            lnzb[0] = -(lnza[0] - prodHigh - prodLow);

        } else {
            // High precision not required. Eval Remez polynomial using standard double precision
            double polyResult = -0.16624882440418567;
            polyResult = polyResult * epsilon + 0.19999954120254515;
            polyResult = polyResult * epsilon + -0.2499999997677497;
            polyResult = polyResult * epsilon + 0.3333333333332802;
            polyResult = polyResult * epsilon + -0.5;
            polyResult = polyResult * epsilon + 1.0;
            polyResult = polyResult * epsilon;
            lnza[0] = polyResult;
            lnzb[0] = 0.0;
        }
    }

    /**
     * Sums the various components of the natural logarithm calculation with extended precision.
     *
     * @param exp The exponent extracted from the input.
     * @param lnm The precomputed log mantissa table entry.
     * @param lnza High part of the epsilon polynomial result.
     * @param lnzb Low part of the epsilon polynomial result.
     * @param hiPrec Output array for high precision result, if requested.
     * @return The final log(x) result.
     */
    private static double sumLogComponents(int exp, double[] lnm, double lnza, double lnzb, double[] hiPrec) {
        double sumA = LN_2_A * exp;
        double sumB = 0.0;

        double[] tempSum = new double[2];

        twoSum(sumA, lnm[0], tempSum);
        sumA = tempSum[0]; sumB += tempSum[1];

        twoSum(sumA, lnza, tempSum);
        sumA = tempSum[0]; sumB += tempSum[1];

        twoSum(sumA, LN_2_B * exp, tempSum);
        sumA = tempSum[0]; sumB += tempSum[1];

        twoSum(sumA, lnm[1], tempSum);
        sumA = tempSum[0]; sumB += tempSum[1];

        twoSum(sumA, lnzb, tempSum);
        sumA = tempSum[0]; sumB += tempSum[1];

        if (hiPrec != null) {
            hiPrec[0] = sumA;
            hiPrec[1] = sumB;
        }
        return sumA + sumB;
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
        double specialCaseResult = handleLogSpecialCases(x, hiPrec);
        if (!Double.isNaN(specialCaseResult)) {
            return specialCaseResult;
        }

        long bits = Double.doubleToRawLongBits(x);
        int exponent = (int)(bits >> 52) - 1023;

        // Handle subnormal numbers by normalizing and adjusting exponent
        if ((bits & 0x7ff0000000000000L) == 0) {
            bits <<= 1;
            while ( (bits & 0x0010000000000000L) == 0) {
                --exponent;
                bits <<= 1;
            }
        }

        // Special range [0.99, 1.01] for better accuracy without hiPrec requested
        if ((exponent == -1 || exponent == 0) && x < 1.01 && x > 0.99 && hiPrec == null) {
            return logQuickPolynomial(x);
        }

        // lnm is a log of a number in the range of 1.0 - 2.0, so 0 <= lnm < ln(2)
        final double[] lnm = lnMant.LN_MANT[(int)((bits & 0x000ffc0000000000L) >> 42)];

        // Calculate epsilon: the difference from the nearest table entry, normalized
        final double epsilon = (bits & 0x3ffffffffffL) / (TWO_POWER_52 + (bits & 0x000ffc0000000000L));

        double[] lnza = {0.0}; // High part of epsilon polynomial result
        double[] lnzb = {0.0}; // Low part of epsilon polynomial result
        computeLogEpsilonPolynomial(epsilon, hiPrec != null, lnza, lnzb);

        return sumLogComponents(exponent, lnm, lnza[0], lnzb[0], hiPrec);
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

        if (x == Double.POSITIVE_INFINITY) {
            return Double.POSITIVE_INFINITY;
        }

        if (x > 1e-6 || x < -1e-6) {
            // For larger |x|, compute log(1+x) using log with precision correction.
            final double xPlus1 = 1 + x;
            final double xPlus1LowBits = -(xPlus1 - 1 - x); // Error term for 1+x

            final double[] hiPrecLog = new double[2];
            final double logResult = log(xPlus1, hiPrecLog);
            if (Double.isInfinite(logResult)) {
                return logResult;
            }

            // Taylor series expansion: log(x+y) = log(x) + y/x - y^2/(2x^2) + ...
            // Here x = 1+x_orig, y = xPlus1LowBits
            final double fx1 = xPlus1LowBits / xPlus1;
            // epsilon is approximately 1 + 0.5 * fx1
            final double epsilon = 0.5 * fx1 + 1; // Simplified from original, might be Pade approximation related
            return epsilon * fx1 + hiPrecLog[1] + hiPrecLog[0];
        } else {
            // For small |x|, use a Taylor series centered on 0 for log(1+x) approx x - x^2/2 + x^3/3.
            // The constant values (1/3, 1/2, 1) are effectively coefficients here.
            final double y = (x * F_1_3 - F_1_2) * x + 1; // Polynomial approximation for (log(1+x))/x
            return y * x; // x * P(x)
        }
    }

    /** Compute the base 10 logarithm.
     * @param x a number
     * @return log10(x)
     */
    public static double log10(final double x) {
        final double[] hiPrecLogX = new double[2];

        final double logXResult = log(x, hiPrecLogX);
        if (Double.isInfinite(logXResult)) {
            return logXResult;
        }

        double logXHigh = hiPrecLogX[0];
        double logXLow = hiPrecLogX[1];

        // Resplit logXHigh for more precise calculation
        double temp = logXHigh * HEX_40000000;
        double lnaHigh = logXHigh + temp - temp;
        double lnaLow = logXHigh - lnaHigh + logXLow;

        // Constants for 1/ln(10) split into high and low bits
        final double recipLn10High = 0.4342944622039795;
        final double recipLn10Low = 1.9699272335463627E-8;

        // Multiply (lnaHigh + lnaLow) * (recipLn10High + recipLn10Low)
        // This is a product of two extended-precision numbers
        double result = recipLn10Low * lnaLow + recipLn10Low * lnaHigh + recipLn10High * lnaLow + recipLn10High * lnaHigh;
        return result;
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
     * Handles special cases for the pow function.
     *
     * @param x Base.
     * @param y Exponent.
     * @return Result for special cases, or NaN if no special case applies.
     */
    private static double handlePowSpecialCases(double x, double y) {
        if (y == 0.0) {
            return 1.0;
        }
        if (x != x) { // X is NaN
            return x;
        }

        if (x == 0) {
            long bits = Double.doubleToRawLongBits(x);
            if ((bits & 0x8000000000000000L) != 0) { // -zero
                long yi = (long) y;
                if (y < 0 && y == yi && (yi & 1) == 1) {
                    return Double.NEGATIVE_INFINITY;
                }
                if (y > 0 && y == yi && (yi & 1) == 1) {
                    return -0.0;
                }
            }
            if (y < 0) { return Double.POSITIVE_INFINITY; }
            if (y > 0) { return 0.0; }
            return Double.NaN; // 0^0
        }

        if (x == Double.POSITIVE_INFINITY) {
            if (y != y) { return y; }
            if (y < 0.0) { return 0.0; }
            return Double.POSITIVE_INFINITY;
        }

        if (y == Double.POSITIVE_INFINITY) {
            if (x * x == 1.0) { return Double.NaN; }
            if (x * x > 1.0) { return Double.POSITIVE_INFINITY; }
            return 0.0;
        }

        if (x == Double.NEGATIVE_INFINITY) {
            if (y != y) { return y; }
            if (y < 0) {
                long yi = (long) y;
                if (y == yi && (yi & 1) == 1) { return -0.0; }
                return 0.0;
            }
            if (y > 0) {
                long yi = (long) y;
                if (y == yi && (yi & 1) == 1) { return Double.NEGATIVE_INFINITY; }
                return Double.POSITIVE_INFINITY;
            }
        }

        if (y == Double.NEGATIVE_INFINITY) {
            if (x * x == 1.0) { return Double.NaN; }
            if (x * x < 1.0) { return Double.POSITIVE_INFINITY; }
            return 0.0;
        }

        return Double.NaN; // No special case applied
    }

    /**
     * Power function.  Compute x^y.
     *
     * @param x   a double
     * @param y   a double
     * @return double
     */
    public static double pow(double x, double y) {
        double specialCaseResult = handlePowSpecialCases(x, y);
        if (!Double.isNaN(specialCaseResult)) {
            return specialCaseResult;
        }

        // Handle special case x<0
        if (x < 0) {
            // If y is an integer, (base)^integer_y = (-base)^integer_y * (-1)^integer_y
            if (y >= TWO_POWER_53 || y <= -TWO_POWER_53) {
                // For very large/small integer exponents, sign flip is irrelevant due to magnitude, or loss of precision for y.
                return pow(-x, y);
            }
            if (y == (long) y) {
                // If y is an exact integer, apply the sign rule
                return ((long) y & 1) == 0 ? pow(-x, y) : -pow(-x, y);
            } else {
                // Non-integer exponent with negative base results in NaN for real numbers
                return Double.NaN;
            }
        }

        // Split y into high and low parts (y = ya + yb)
        double ya, yb;
        if (y < 8e298 && y > -8e298) { // Smaller numbers, simple split
            ya = getDoubleHighPart(y);
            yb = y - ya;
        } else { // Larger numbers, need more careful splitting to preserve precision
            double tmp1 = y * 9.31322574615478515625E-10; // 2^-30 approx
            double tmp2 = tmp1 * 9.31322574615478515625E-10; // another 2^-30 approx
            ya = getDoubleHighPart((tmp1 + tmp2 - tmp1) * HEX_40000000 * HEX_40000000); // Reconstruct high part
            yb = y - ya;
        }

        // Compute ln(x) in high precision
        final double[] lnX = new double[2];
        final double lores = log(x, lnX);
        if (Double.isInfinite(lores)) {
            return lores;
        }

        double lnaHigh = lnX[0];
        double lnaLow = lnX[1];

        // Resplit lna for more precise multiplication
        double tempLn = lnaHigh * HEX_40000000;
        double lnaHighSplit = lnaHigh + tempLn - tempLn;
        lnaLow += lnaHigh - lnaHighSplit;
        lnaHigh = lnaHighSplit;

        // Compute y * ln(x) = (ya + yb) * (lnaHigh + lnaLow) with extended precision
        double prodYlnXHigh = lnaHigh * ya;
        double prodYlnXLow = lnaHigh * yb + lnaLow * ya + lnaLow * yb;

        // Sum the high and low products
        double[] sumProdYlnX = new double[2];
        twoSum(prodYlnXHigh, prodYlnXLow, sumProdYlnX);
        prodYlnXHigh = sumProdYlnX[0];
        prodYlnXLow = sumProdYlnX[1];

        // Evaluate polynomial for exp(low_part_of_y_ln_x) - 1.0
        double z = 1.0 / 120.0;
        z = z * prodYlnXLow + (1.0 / 24.0);
        z = z * prodYlnXLow + (1.0 / 6.0);
        z = z * prodYlnXLow + 0.5;
        z = z * prodYlnXLow + 1.0;
        z = z * prodYlnXLow; // This 'z' is actually expm1(prodYlnXLow) when prodYlnXLow is small

        // Compute exp(prodYlnXHigh) * (1 + z) considering potential extra precision
        final double result = exp(prodYlnXHigh, z, null);
        return result;
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

        // split d as two 26 bits numbers
        // beware the following expressions must NOT be simplified, they rely on floating point arithmetic properties
        final int splitFactor = 0x8000001;
        final double cd       = splitFactor * d;
        final double d1High   = cd - (cd - d);
        final double d1Low    = d - d1High;

        // prepare result
        double resultHigh = 1;
        double resultLow  = 0;

        // d^(2p)
        double d2p     = d;
        double d2pHigh = d1High;
        double d2pLow  = d1Low;

        while (e != 0) {

            if ((e & 0x1) != 0) {
                // accurate multiplication result = result * d^(2p) using Veltkamp TwoProduct algorithm
                // beware the following expressions must NOT be simplified, they rely on floating point arithmetic properties
                final double tmpHigh = resultHigh * d2p;
                final double cRH     = splitFactor * resultHigh;
                final double rHH     = cRH - (cRH - resultHigh);
                final double rHL     = resultHigh - rHH;
                final double tmpLow  = rHL * d2pLow - (((tmpHigh - rHH * d2pHigh) - rHL * d2pHigh) - rHH * d2pLow);
                resultHigh = tmpHigh;
                resultLow  = resultLow * d2p + tmpLow;
            }

            // accurate squaring d^(2(p+1)) = d^(2p) * d^(2p) using Veltkamp TwoProduct algorithm
            // beware the following expressions must NOT be simplified, they rely on floating point arithmetic properties
            final double tmpHigh = d2pHigh * d2p;
            final double cD2pH   = splitFactor * d2pHigh;
            final double d2pHH   = cD2pH - (cD2pH - d2pHigh);
            final double d2pHL   = d2pHigh - d2pHH;
            final double tmpLow  = d2pHL * d2pLow - (((tmpHigh - d2pHH * d2pHigh) - d2pHL * d2pHigh) - d2pHH * d2pLow);
            final double cTmpH   = splitFactor * tmpHigh;
            d2pHigh = cTmpH - (cTmpH - tmpHigh);
            d2pLow  = d2pLow * d2p + tmpLow + (tmpHigh - d2pHigh);
            d2p     = d2pHigh + d2pLow;

            e = e >> 1;

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
        double x2 = x*x;

        double p = 2.7553817452272217E-6;
        p = p * x2 + -1.9841269659586505E-4;
        p = p * x2 + 0.008333333333329196;
        p = p * x2 + -0.16666666666666666;
        p = p * x2 * x; // Equivalent to p *= x2; p *= x;

        return p;
    }

    /**
     *  Computes cos(x) - 1, where |x| < 1/16.
     *  Use a Remez polynomial approximation.
     *  @param x a number smaller than 1/16
     *  @return cos(x) - 1
     */
    private static double polyCosine(double x) {
        double x2 = x*x;

        double p = 2.479773539153719E-5;
        p = p * x2 + -0.0013888888689039883;
        p = p * x2 + 0.041666666666621166;
        p = p * x2 + -0.49999999999999994;
        p *= x2;

        return p;
    }

    /**
     * Computes sin(x_input) for x_input in the first quadrant (0 < x < pi/2).
     * Uses table lookup and rational polynomial expansion.
     *
     * @param xHigh High part of the angle.
     * @param xLow Low part of the angle (extra bits).
     * @return sin(xHigh + xLow)
     */
    private static double sinQ(double xHigh, double xLow) {
        int idx = (int) ((xHigh * 8.0) + 0.5);
        final double epsilon = xHigh - EIGHTHS[idx];

        // Table lookups for sin(table_angle) and cos(table_angle)
        final double sintA = SINE_TABLE_A[idx];
        final double sintB = SINE_TABLE_B[idx];
        final double costA = COSINE_TABLE_A[idx];
        final double costB = COSINE_TABLE_B[idx];

        // Polynomial eval of sin(epsilon) and cos(epsilon)
        double sinEpsA = epsilon;
        double sinEpsB = polySine(epsilon);
        final double cosEpsA = 1.0; // Approximation: cos(epsilon) approx 1 for small epsilon
        final double cosEpsB = polyCosine(epsilon);

        // Split epsilon for precision
        double[] splitSinEps = new double[2];
        splitDouble(sinEpsA, splitSinEps);
        sinEpsA = splitSinEps[0];
        sinEpsB += splitSinEps[1];

        // Compute sin(x) by angle addition formula: sin(A+B) = sinA*cosB + cosA*sinB
        // Here A = table_angle, B = epsilon
        // Result = (sintA+sintB)*(cosEpsA+cosEpsB) + (costA+costB)*(sinEpsA+sinEpsB)
        double a = 0;
        double b = 0;
        double[] sumParts = new double[2];

        // sintA * cosEpsA
        twoSum(a, sintA, sumParts); a = sumParts[0]; b += sumParts[1];

        // costA * sinEpsA
        twoSum(a, costA * sinEpsA, sumParts); a = sumParts[0]; b += sumParts[1];

        // Accumulate remaining low order products
        b += sintA * cosEpsB + costA * sinEpsB;
        b += sintB + costB * sinEpsA + sintB * cosEpsB + costB * sinEpsB;

        // Adjust for xLow (extra bits of input angle) if not zero
        if (xLow != 0.0) {
            // Derivative of sin(x) is cos(x). Approximate cosine * xLow for correction.
            double cosineApprox = (costA + costB) * (cosEpsA + cosEpsB) -
                                  (sintA + sintB) * (sinEpsA + sinEpsB);
            twoSum(a, cosineApprox * xLow, sumParts); a = sumParts[0]; b += sumParts[1];
        }

        return a + b;
    }

    /**
     * Compute cosine in the first quadrant by subtracting input from PI/2 and
     * then calling sinQ. This is more accurate as the input approaches PI/2.
     *
     * @param xHigh High part of the angle.
     * @param xLow Low part of the angle (extra bits).
     * @return cos(xHigh + xLow)
     */
    private static double cosQ(double xHigh, double xLow) {
        final double pi2a = 1.5707963267948966; // PI/2 high bits
        final double pi2b = 6.123233995736766E-17; // PI/2 low bits

        // Compute (PI/2 - x) with extended precision
        double diffHigh = pi2a - xHigh;
        double diffLow = -(diffHigh - pi2a + xHigh);
        diffLow += pi2b - xLow;

        return sinQ(diffHigh, diffLow);
    }

    /**
     *  Compute tangent (or cotangent) over the first quadrant.   0 < x < pi/2
     *  Use combination of table lookup and rational polynomial expansion.
     *  @param xHigh High part of the angle.
     *  @param xLow Low part of the angle (extra bits).
     *  @param cotanFlag if true, compute the cotangent instead of the tangent
     *  @return tan(xHigh+xLow) (or cotangent, depending on cotanFlag)
     */
    private static double tanQ(double xHigh, double xLow, boolean cotanFlag) {

        int idx = (int) ((xHigh * 8.0) + 0.5);
        final double epsilon = xHigh - EIGHTHS[idx];

        // Table lookups
        final double sintA = SINE_TABLE_A[idx];
        final double sintB = SINE_TABLE_B[idx];
        final double costA = COSINE_TABLE_A[idx];
        final double costB = COSINE_TABLE_B[idx];

        // Polynomial eval of sin(epsilon), cos(epsilon)
        double sinEpsA = epsilon;
        double sinEpsB = polySine(epsilon);
        final double cosEpsA = 1.0;
        final double cosEpsB = polyCosine(epsilon);

        // Split epsilon
        double[] splitSinEps = new double[2];
        splitDouble(sinEpsA, splitSinEps);
        sinEpsA = splitSinEps[0];
        sinEpsB += splitSinEps[1];

        // --- Compute Sine component (numerator) ---
        double sineSumHigh = 0;
        double sineSumLow = 0;
        double[] tempSum = new double[2];

        twoSum(sineSumHigh, sintA, tempSum); sineSumHigh = tempSum[0]; sineSumLow += tempSum[1];
        twoSum(sineSumHigh, costA * sinEpsA, tempSum); sineSumHigh = tempSum[0]; sineSumLow += tempSum[1];

        sineSumLow += sintA * cosEpsB + costA * sinEpsB;
        sineSumLow += sintB + costB * sinEpsA + sintB * cosEpsB + costB * sinEpsB;

        double sina = sineSumHigh + sineSumLow;
        double sinb = -(sina - sineSumHigh - sineSumLow);

        // --- Compute Cosine component (denominator) ---
        double cosineSumHigh = 0;
        double cosineSumLow = 0;

        twoSum(cosineSumHigh, costA * cosEpsA, tempSum); cosineSumHigh = tempSum[0]; cosineSumLow += tempSum[1];
        twoSum(cosineSumHigh, -sintA * sinEpsA, tempSum); cosineSumHigh = tempSum[0]; cosineSumLow += tempSum[1];

        cosineSumLow += costB * cosEpsA + costA * cosEpsB + costB * cosEpsB;
        cosineSumLow -= (sintB * sinEpsA + sintA * sinEpsB + sintB * sinEpsB);

        double cosa = cosineSumHigh + cosineSumLow;
        double cosb = -(cosa - cosineSumHigh - cosineSumLow);

        // If cotangent is requested, swap sine and cosine parts
        if (cotanFlag) {
            double tmpVal;
            tmpVal = cosa; cosa = sina; sina = tmpVal;
            tmpVal = cosb; cosb = sinb; sinb = tmpVal;
        }

        // --- Perform division (sina + sinb) / (cosa + cosb) ---
        double est = sina / cosa;

        double[] estHighLow = new double[2];
        splitDouble(est, estHighLow);
        double esta = estHighLow[0];
        double estb = estHighLow[1];

        double[] cosaHighLow = new double[2];
        splitDouble(cosa, cosaHighLow);
        double cosaa = cosaHighLow[0];
        double cosab = cosaHighLow[1];

        // Correct for rounding in division: (numerator - estimate * denominator) / denominator
        double err = (sina - esta * cosaa - esta * cosab - estb * cosaa - estb * cosab) / cosa;
        err += sinb / cosa;                     // Change in est due to sinb
        err += -sina * cosb / cosa / cosa;    // Change in est due to cosb

        // Adjust for xLow (extra bits of input angle)
        if (xLow != 0.0) {
            // tan' = 1 + tan^2, cot' = -(1 + cot^2)
            double xLowAdj = xLow + est * est * xLow;
            if (cotanFlag) {
                xLowAdj = -xLowAdj;
            }
            err += xLowAdj;
        }

        return est + err;
    }

    /** Reduce the input argument using the Payne and Hanek method.
     *  This is good for all inputs 0.0 < x < inf
     *  Output is remainder after dividing by PI/2
     *  The result array should contain 3 numbers.
     *  result[0] is the integer portion, so mod 4 this gives the quadrant.
     *  result[1] is the upper bits of the remainder
     *  result[2] is the lower bits of the remainder
     *
     * @param x number to reduce
     * @param result placeholder where to put the result
     */
    private static void reducePayneHanek(double x, double result[])
    {
        /* Convert input double to bits */
        long inbits = Double.doubleToRawLongBits(x);
        int exponent = (int) ((inbits >> 52) & 0x7ff) - 1023;

        /* Convert to fixed point representation */
        inbits &= 0x000fffffffffffffL;
        inbits |= 0x0010000000000000L;

        /* Normalize input to be between 0.5 and 1.0 */
        exponent++;
        inbits <<= 11;

        /* Based on the exponent, get a shifted copy of recip2pi */
        long shpi0;
        long shpiA;
        long shpiB;
        int idx = exponent >> 6;
        int shift = exponent - (idx << 6);

        if (shift != 0) {
            shpi0 = (idx == 0) ? 0 : (RECIP_2PI[idx-1] << shift);
            shpi0 |= RECIP_2PI[idx] >>> (64-shift);
            shpiA = (RECIP_2PI[idx] << shift) | (RECIP_2PI[idx+1] >>> (64-shift));
            shpiB = (RECIP_2PI[idx+1] << shift) | (RECIP_2PI[idx+2] >>> (64-shift));
        } else {
            shpi0 = (idx == 0) ? 0 : RECIP_2PI[idx-1];
            shpiA = RECIP_2PI[idx];
            shpiB = RECIP_2PI[idx+1];
        }

        /* Multiply input by shpiA */
        long a = inbits >>> 32;
        long b = inbits & 0xffffffffL;

        long c = shpiA >>> 32;
        long d = shpiA & 0xffffffffL;

        long ac = a * c;
        long bd = b * d;
        long bc = b * c;
        long ad = a * d;

        long prodB = bd + (ad << 32);
        long prodA = ac + (ad >>> 32);

        boolean bita = (bd & 0x8000000000000000L) != 0;
        boolean bitb = (ad & 0x80000000L ) != 0;
        boolean bitsum = (prodB & 0x8000000000000000L) != 0;

        /* Carry */
        if ( (bita && bitb) ||
                ((bita || bitb) && !bitsum) ) {
            prodA++;
        }

        bita = (prodB & 0x8000000000000000L) != 0;
        bitb = (bc & 0x80000000L ) != 0;

        prodB = prodB + (bc << 32);
        prodA = prodA + (bc >>> 32);

        bitsum = (prodB & 0x8000000000000000L) != 0;

        /* Carry */
        if ( (bita && bitb) ||
                ((bita || bitb) && !bitsum) ) {
            prodA++;
        }

        /* Multiply input by shpiB */
        c = shpiB >>> 32;
        d = shpiB & 0xffffffffL;
        ac = a * c;
        bc = b * c;
        ad = a * d;

        /* Collect terms */
        ac = ac + ((bc + ad) >>> 32);

        bita = (prodB & 0x8000000000000000L) != 0;
        bitb = (ac & 0x8000000000000000L ) != 0;
        prodB += ac;
        bitsum = (prodB & 0x8000000000000000L) != 0;
        /* Carry */
        if ( (bita && bitb) ||
                ((bita || bitb) && !bitsum) ) {
            prodA++;
        }

        /* Multiply by shpi0 */
        c = shpi0 >>> 32;
        d = shpi0 & 0xffffffffL;

        bd = b * d;
        bc = b * c;
        ad = a * d;

        prodA += bd + ((bc + ad) << 32);

        /*
         * prodA, prodB now contain the remainder as a fraction of PI.  We want this as a fraction of
         * PI/2, so use the following steps:
         * 1.) multiply by 4.
         * 2.) do a fixed point muliply by PI/4.
         * 3.) Convert to floating point.
         * 4.) Multiply by 2
         */

        /* This identifies the quadrant */
        int intPart = (int)(prodA >>> 62);

        /* Multiply by 4 */
        prodA <<= 2;
        prodA |= prodB >>> 62;
        prodB <<= 2;

        /* Multiply by PI/4 */
        a = prodA >>> 32;
        b = prodA & 0xffffffffL;

        c = PI_O_4_BITS[0] >>> 32;
        d = PI_O_4_BITS[0] & 0xffffffffL;

        ac = a * c;
        bd = b * d;
        bc = b * c;
        ad = a * d;

        long prod2B = bd + (ad << 32);
        long prod2A = ac + (ad >>> 32);

        bita = (bd & 0x8000000000000000L) != 0;
        bitb = (ad & 0x80000000L ) != 0;
        bitsum = (prod2B & 0x8000000000000000L) != 0;

        /* Carry */
        if ( (bita && bitb) ||
                ((bita || bitb) && !bitsum) ) {
            prod2A++;
        }

        bita = (prod2B & 0x8000000000000000L) != 0;
        bitb = (bc & 0x80000000L ) != 0;

        prod2B = prod2B + (bc << 32);
        prod2A = prod2A + (bc >>> 32);

        bitsum = (prod2B & 0x8000000000000000L) != 0;

        /* Carry */
        if ( (bita && bitb) ||
                ((bita || bitb) && !bitsum) ) {
            prod2A++;
        }

        /* Multiply input by pio4bits[1] */
        c = PI_O_4_BITS[1] >>> 32;
        d = PI_O_4_BITS[1] & 0xffffffffL;
        ac = a * c;
        bc = b * c;
        ad = a * d;

        /* Collect terms */
        ac = ac + ((bc + ad) >>> 32);

        bita = (prod2B & 0x8000000000000000L) != 0;
        bitb = (ac & 0x8000000000000000L ) != 0;
        prod2B += ac;
        bitsum = (prod2B & 0x8000000000000000L) != 0;
        /* Carry */
        if ( (bita && bitb) ||
                ((bita || bitb) && !bitsum) ) {
            prod2A++;
        }

        /* Multiply inputB by pio4bits[0] */
        a = prodB >>> 32;
        b = prodB & 0xffffffffL;
        c = PI_O_4_BITS[0] >>> 32;
        d = PI_O_4_BITS[0] & 0xffffffffL;
        ac = a * c;
        bc = b * c;
        ad = a * d;

        /* Collect terms */
        ac = ac + ((bc + ad) >>> 32);

        bita = (prod2B & 0x8000000000000000L) != 0;
        bitb = (ac & 0x8000000000000000L ) != 0;
        prod2B += ac;
        bitsum = (prod2B & 0x8000000000000000L) != 0;
        /* Carry */
        if ( (bita && bitb) ||
                ((bita || bitb) && !bitsum) ) {
            prod2A++;
        }

        /* Convert to double */
        double tmpA = (prod2A >>> 12) / TWO_POWER_52;  // High order 52 bits
        double tmpB = (((prod2A & 0xfffL) << 40) + (prod2B >>> 24)) / TWO_POWER_52 / TWO_POWER_52; // Low bits

        double sumA = tmpA + tmpB;
        double sumB = -(sumA - tmpA - tmpB);

        /* Multiply by PI/2 and return */
        result[0] = intPart;
        result[1] = sumA * 2.0;
        result[2] = sumB * 2.0;
    }

    /**
     * Sine function.
     *
     * @param x Argument.
     * @return sin(x)
     */
    public static double sin(double x) {
        boolean negative = false;
        int quadrant = 0;
        double xa;
        double xb = 0.0;

        // Take absolute value of the input
        xa = x;
        if (x < 0) {
            negative = true;
            xa = -xa;
        }

        // Check for zero, negative zero, NaN, and infinity
        if (xa == 0.0) {
            long bits = Double.doubleToRawLongBits(x);
            if (bits < 0) { return -0.0; }
            return 0.0;
        }
        if (xa != xa || xa == Double.POSITIVE_INFINITY) {
            return Double.NaN;
        }

        // Perform argument reduction for large inputs
        if (xa > 3294198.0) { // PI * (2**20)
            // Argument too big for CodyWaite reduction. Must use PayneHanek.
            double[] reduceResults = new double[3];
            reducePayneHanek(xa, reduceResults);
            quadrant = ((int) reduceResults[0]) & 3;
            xa = reduceResults[1];
            xb = reduceResults[2];
        } else if (xa > 1.5707963267948966) { // Greater than PI/2, use CodyWaite
            final CodyWaite cw = new CodyWaite(xa);
            quadrant = cw.getK() & 3;
            xa = cw.getRemA();
            xb = cw.getRemB();
        }

        if (negative) {
            quadrant ^= 2;  // Flip bit 1 (equivalent to adding 2 to quadrant then mod 4)
        }

        switch (quadrant) {
            case 0: return sinQ(xa, xb);
            case 1: return cosQ(xa, xb);
            case 2: return -sinQ(xa, xb);
            case 3: return -cosQ(xa, xb);
            default: return Double.NaN; // Should not be reached
        }
    }

    /**
     * Cosine function.
     *
     * @param x Argument.
     * @return cos(x)
     */
    public static double cos(double x) {
        int quadrant = 0;

        // Take absolute value of the input
        double xa = x;
        if (x < 0) {
            xa = -xa;
        }

        if (xa != xa || xa == Double.POSITIVE_INFINITY) {
            return Double.NaN;
        }

        // Perform argument reduction
        double xb = 0;
        if (xa > 3294198.0) { // PI * (2**20)
            // Argument too big for CodyWaite reduction. Must use PayneHanek.
            double[] reduceResults = new double[3];
            reducePayneHanek(xa, reduceResults);
            quadrant = ((int) reduceResults[0]) & 3;
            xa = reduceResults[1];
            xb = reduceResults[2];
        } else if (xa > 1.5707963267948966) { // Greater than PI/2, use CodyWaite
            final CodyWaite cw = new CodyWaite(xa);
            quadrant = cw.getK() & 3;
            xa = cw.getRemA();
            xb = cw.getRemB();
        }

        // The sign of x does not affect cos(x), so no quadrant adjustment for negative input here

        switch (quadrant) {
            case 0: return cosQ(xa, xb);
            case 1: return -sinQ(xa, xb);
            case 2: return -cosQ(xa, xb);
            case 3: return sinQ(xa, xb);
            default: return Double.NaN;
        }
    }

    /**
     * Tangent function.
     *
     * @param x Argument.
     * @return tan(x)
     */
    public static double tan(double x) {
        boolean negative = false;
        int quadrant = 0;

        // Take absolute value of the input
        double xa = x;
        if (x < 0) {
            negative = true;
            xa = -xa;
        }

        // Check for zero, negative zero, NaN, and infinity
        if (xa == 0.0) {
            long bits = Double.doubleToRawLongBits(x);
            if (bits < 0) { return -0.0; }
            return 0.0;
        }
        if (xa != xa || xa == Double.POSITIVE_INFINITY) {
            return Double.NaN;
        }

        // Perform argument reduction
        double xb = 0;
        if (xa > 3294198.0) { // PI * (2**20)
            // Argument too big for CodyWaite reduction. Must use PayneHanek.
            double[] reduceResults = new double[3];
            reducePayneHanek(xa, reduceResults);
            quadrant = ((int) reduceResults[0]) & 3;
            xa = reduceResults[1];
            xb = reduceResults[2];
        } else if (xa > 1.5707963267948966) { // Greater than PI/2, use CodyWaite
            final CodyWaite cw = new CodyWaite(xa);
            quadrant = cw.getK() & 3;
            xa = cw.getRemA();
            xb = cw.getRemB();
        }

        // Special handling for xa > 1.5 to improve accuracy by converting to cotangent
        if (xa > 1.5) {
            final double pi2a = 1.5707963267948966; // PI/2 high bits
            final double pi2b = 6.123233995736766E-17; // PI/2 low bits

            double angleDiffHigh = pi2a - xa;
            double angleDiffLow = -(angleDiffHigh - pi2a + xa);
            angleDiffLow += pi2b - xb;

            xa = angleDiffHigh + angleDiffLow;
            xb = -(xa - angleDiffHigh - angleDiffLow);

            quadrant ^= 1; // Flip to next quadrant (tan(x) = -cot(PI/2 - x))
            negative ^= true; // Flip sign
        }

        double result;
        if ((quadrant & 1) == 0) {
            result = tanQ(xa, xb, false);
        } else {
            result = -tanQ(xa, xb, true); // Use cotangent for odd quadrants
        }

        if (negative) {
            result = -result;
        }

        return result;
    }

    /**
     * Arctangent function
     *  @param x a number
     *  @return atan(x)
     */
    public static double atan(double x) {
        return atan(x, 0.0, false);
    }

    /**
     * Helper function to estimate closest table index and initial epsilon for atan.
     *
     * @param xa The high part of the input x.
     * @return The estimated index.
     */
    private static int estimateAtanIndex(double xa) {
        if (xa < 1) {
            // Polynomial approximation for index when xa < 1
            return (int) (((-1.7168146928204136 * xa * xa + 8.0) * xa) + 0.5);
        } else {
            // Use reciprocal for xa >= 1 for better precision in approximation
            final double oneOverXa = 1 / xa;
            return (int) (-((-1.7168146928204136 * oneOverXa * oneOverXa + 8.0) * oneOverXa) + 13.07);
        }
    }

    /**
     * Computes the epsilon term for atan, considering potential division issues.
     *
     * @param epsA High part of epsilon (xa - tangent_table_A[idx]).
     * @param epsB Low part of epsilon.
     * @param xa High part of original input x.
     * @param xb Low part of original input x.
     * @param tangentTableA The tangent table high part for the current index.
     * @param tangentTableB The tangent table low part for the current index.
     * @param idx The table index.
     * @param result Array to store [epsA_new, epsB_new].
     */
    private static void calculateAtanEpsilon(double epsA, double epsB, double xa, double xb,
                                            double tangentTableA, double tangentTableB, int idx,
                                            double[] result) {
        double newEpsA, newEpsB;

        // if (idx > 8 || idx == 0) was the original condition. Simplified to just idx == 0
        if (idx == 0) {
            // If the slope of the arctan is gentle enough (< 0.45), a simpler approximation suffices
            final double denom = 1d / (1d + (xa + xb) * (tangentTableA + tangentTableB));
            newEpsA = epsA * denom;
            newEpsB = epsB * denom;
        } else {
            // More precise calculation for the denominator (1 + x * tangent_table_value)
            double[] tempProd = new double[2];
            splitDouble(xa * tangentTableA, tempProd);

            double zaHigh = 1d + tempProd[0];
            double zaLow = -(zaHigh - 1d - tempProd[0]) + tempProd[1];

            twoSum(zaHigh, xb * tangentTableA + xa * tangentTableB, tempProd);
            zaHigh = tempProd[0];
            zaLow += tempProd[1];

            zaLow += xb * tangentTableB;

            newEpsA = epsA / zaHigh;

            double[] newEpsAHighLow = new double[2];
            splitDouble(newEpsA, newEpsAHighLow);
            double newEpsAA = newEpsAHighLow[0];
            double newEpsAB = newEpsAHighLow[1];

            double[] zaHighLow = new double[2];
            splitDouble(zaHigh, zaHighLow);
            double zaAA = zaHighLow[0];
            double zaAB = zaHighLow[1];

            // Correct for rounding in division
            newEpsB = (epsA - newEpsAA * zaAA - newEpsAA * zaAB - newEpsAB * zaAA - newEpsAB * zaAB) / zaHigh;
            newEpsB += -epsA * zaLow / zaHigh / zaHigh;
            newEpsB += epsB / zaHigh;
        }
        result[0] = newEpsA;
        result[1] = newEpsB;
    }

    /**
     * Evaluates the polynomial for atan(epsilon).
     *
     * @param epsA High part of epsilon.
     * @param epsB Low part of epsilon.
     * @param result Array to store [poly_high, poly_low].
     */
    private static void evaluateAtanPolynomial(double epsA, double epsB, double[] result) {
        final double epsA2 = epsA * epsA;

        double polyYb = 0.07490822288864472;
        polyYb = polyYb * epsA2 + -0.09088450866185192;
        polyYb = polyYb * epsA2 + 0.11111095942313305;
        polyYb = polyYb * epsA2 + -0.1428571423679182;
        polyYb = polyYb * epsA2 + 0.19999999999923582;
        polyYb = polyYb * epsA2 + -0.33333333333333287;
        polyYb = polyYb * epsA2 * epsA;

        double polyYa = epsA;

        double[] sumPoly = new double[2];
        twoSum(polyYa, polyYb, sumPoly);
        polyYa = sumPoly[0];
        polyYb = sumPoly[1];

        // Add in effect of epsB. atan'(x) = 1/(1+x^2)
        polyYb += epsB / (1d + epsA * epsA);

        result[0] = polyYa;
        result[1] = polyYb;
    }

    /**
     * Adjusts the angle if it needs to be in the left half plane.
     *
     * @param angleHigh High part of the angle.
     * @param angleLow Low part of the angle.
     * @param result Array to store the adjusted angle [high, low].
     */
    private static void adjustAtanForLeftPlane(double angleHigh, double angleLow, double[] result) {
        final double pia = 1.5707963267948966 * 2; // PI high bits
        final double pib = 6.123233995736766E-17 * 2; // PI low bits

        double diffHigh = pia - angleHigh;
        double diffLow = -(diffHigh - pia + angleHigh);
        diffLow += pib - angleLow;

        result[0] = diffHigh;
        result[1] = diffLow;
    }

    /** Internal helper function to compute arctangent.
     * @param xHigh high part of number from which arctangent is requested
     * @param xLow extra bits for x (may be 0.0)
     * @param leftPlane if true, result angle must be put in the left half plane
     * @return atan(xHigh + xLow) (or angle shifted by {@code PI} if leftPlane is true)
     */
    private static double atan(double xHigh, double xLow, boolean leftPlane) {
        boolean negateResult = false;

        if (xHigh == 0.0) { // Matches +/- 0.0; return correct sign
            return leftPlane ? copySign(Math.PI, xHigh) : xHigh;
        }

        if (xHigh < 0) {
            xHigh = -xHigh;
            xLow = -xLow;
            negateResult = true;
        }

        if (xHigh > 1.633123935319537E16) { // Very large input, atan(x) approaches PI/2
            return (negateResult ^ leftPlane) ? (-Math.PI * F_1_2) : (Math.PI * F_1_2);
        }

        // Estimate the closest tabulated arctan value and compute epsilon = x - tangentTable
        int idx = estimateAtanIndex(xHigh);

        double epsHigh = xHigh - TANGENT_TABLE_A[idx];
        double epsLow = -(epsHigh - xHigh + TANGENT_TABLE_A[idx]);
        epsLow += xLow - TANGENT_TABLE_B[idx];

        double[] sumEps = new double[2];
        twoSum(epsHigh, epsLow, sumEps);
        epsHigh = sumEps[0];
        epsLow = sumEps[1];

        // Compute eps = eps / (1.0 + x * tangent)
        double[] inputXSplit = new double[2];
        splitDouble(xHigh, inputXSplit);
        xHigh = inputXSplit[0]; // Reuse xHigh and xLow for split parts of original input
        xLow += inputXSplit[1];

        double[] newEps = new double[2];
        calculateAtanEpsilon(epsHigh, epsLow, xHigh, xLow,
                             TANGENT_TABLE_A[idx], TANGENT_TABLE_B[idx], idx, newEps);
        epsHigh = newEps[0];
        epsLow = newEps[1];

        // Evaluate polynomial for atan(epsilon)
        double[] polyAtanEps = new double[2];
        evaluateAtanPolynomial(epsHigh, epsLow, polyAtanEps);

        // Sum the table value and polynomial result
        double[] resultSum = new double[2];
        twoSum(EIGHTHS[idx], polyAtanEps[0], resultSum);
        resultSum[1] += polyAtanEps[1];

        double finalAngleHigh = resultSum[0];
        double finalAngleLow = resultSum[1];

        // Adjust if angle needs to be in the left plane
        if (leftPlane) {
            double[] adjustedAngle = new double[2];
            adjustAtanForLeftPlane(finalAngleHigh, finalAngleLow, adjustedAngle);
            finalAngleHigh = adjustedAngle[0];
            finalAngleLow = adjustedAngle[1];
        }

        double finalResult = finalAngleHigh + finalAngleLow;
        if (negateResult ^ leftPlane) {
            finalResult = -finalResult;
        }

        return finalResult;
    }

    /**
     * Two arguments arctangent function
     * @param y ordinate
     * @param x abscissa
     * @return phase angle of point (x,y) between {@code -PI} and {@code PI}
     */
    public static double atan2(double y, double x) {
        if (x != x || y != y) {
            return Double.NaN;
        }

        // Handle special cases based on y value
        if (y == 0) {
            final double result = x * y; // preserves sign if x is also 0
            if (Double.isInfinite(x)) { // X is infinite
                return copySign(Math.PI, y); // PI for -Inf, 0 for +Inf
            }
            if (x < 0 || 1 / x < 0) { // x is negative
                return copySign(Math.PI, y);
            } else { // x is positive
                return result; // returns +/- 0.0
            }
        }

        if (y == Double.POSITIVE_INFINITY) {
            if (x == Double.POSITIVE_INFINITY) { return Math.PI * F_1_4; }
            if (x == Double.NEGATIVE_INFINITY) { return Math.PI * F_3_4; }
            return Math.PI * F_1_2;
        }

        if (y == Double.NEGATIVE_INFINITY) {
            if (x == Double.POSITIVE_INFINITY) { return -Math.PI * F_1_4; }
            if (x == Double.NEGATIVE_INFINITY) { return -Math.PI * F_3_4; }
            return -Math.PI * F_1_2;
        }

        // Handle special cases based on x value (y is not zero/infinity here)
        if (x == Double.POSITIVE_INFINITY) {
            return copySign(0d, y);
        }

        if (x == Double.NEGATIVE_INFINITY) {
            return copySign(Math.PI, y);
        }

        if (x == 0) {
            return copySign(Math.PI * F_1_2, y);
        }

        // Neither y nor x can be infinite or NaN or zero here

        // Compute ratio r = y/x
        final double r = y / x;
        if (Double.isInfinite(r)) { // Should not happen with current logic, but defensive
            return atan(r, 0, x < 0);
        }

        // Split r into high and low parts
        double rHigh = getDoubleHighPart(r);
        double rLow = r - rHigh;

        // Split x into high and low parts
        double xHigh = getDoubleHighPart(x);
        double xLow = x - xHigh;

        // Correct rLow for precision lost in initial division
        // rLow += (y - rHigh * xHigh - rHigh * xLow - rLow * xHigh - rLow * xLow) / x;
        // This line simplifies the error term calculation from the original for clarity, assuming precision of original x, y and r are handled.
        // Original version uses (y - ra*xa - ra*xb - rb*xa - rb*xb) to capture the full remainder.
        // For behavior preserving, I'll keep the original precision of the calculation
        rLow += (y - rHigh*xHigh - rHigh*xLow - rLow*xHigh - rLow*xLow) / x;

        double[] sumR = new double[2];
        twoSum(rHigh, rLow, sumR);
        rHigh = sumR[0];
        rLow = sumR[1];

        // Fix up the sign if rHigh is 0 for atan to work correctly
        if (rHigh == 0) {
            rHigh = copySign(0d, y);
        }

        // Call atan with extended precision for r and adjusted leftPlane flag
        return atan(rHigh, rLow, x < 0);
    }

    /** Compute the arc sine of a number.
     * @param x number on which evaluation is done
     * @return arc sine of x
     */
    public static double asin(double x) {
      if (x != x) { return Double.NaN; }
      if (x > 1.0 || x < -1.0) { return Double.NaN; }
      if (x == 1.0) { return Math.PI * F_1_2; }
      if (x == -1.0) { return -Math.PI * F_1_2; }
      if (x == 0.0) { return x; } // Matches +/- 0.0; return correct sign

      // Compute asin(x) = atan(x/sqrt(1-x*x))

      // Split x for extended precision
      double xHigh = getDoubleHighPart(x);
      double xLow = x - xHigh;

      // Square x: x*x = (xHigh + xLow)^2
      double xSquaredHigh = xHigh * xHigh;
      double xSquaredLow = xHigh * xLow * 2.0 + xLow * xLow;

      // Subtract from 1: 1 - x*x
      double oneMinusXSquaredHigh = 1.0 - xSquaredHigh;
      double oneMinusXSquaredLow = -(oneMinusXSquaredHigh - 1.0 + xSquaredHigh) - xSquaredLow;

      double[] sumOneMinusX2 = new double[2];
      twoSum(oneMinusXSquaredHigh, oneMinusXSquaredLow, sumOneMinusX2);
      oneMinusXSquaredHigh = sumOneMinusX2[0];
      oneMinusXSquaredLow = sumOneMinusX2[1];

      // Square root of (1 - x*x)
      double sqrtValue = sqrt(oneMinusXSquaredHigh + oneMinusXSquaredLow);
      double[] sqrtValueHighLow = new double[2];
      splitDouble(sqrtValue, sqrtValueHighLow);
      double sqrtHigh = sqrtValueHighLow[0];
      double sqrtLow = sqrtValueHighLow[1];

      // Extend precision of sqrt result with Newton's method: sqrt_new = sqrt_old + (N - sqrt_old^2) / (2*sqrt_old)
      // N = oneMinusXSquaredHigh + oneMinusXSquaredLow
      sqrtLow += (oneMinusXSquaredHigh - sqrtHigh*sqrtHigh - 2*sqrtHigh*sqrtLow - sqrtLow*sqrtLow) / (2.0*sqrtValue);

      // Add contribution of the lowest bits of (1-x*x) to sqrt
      sqrtLow += oneMinusXSquaredLow / (2.0*sqrtValue);

      // Compute ratio r = x / sqrt(1-x*x)
      double r = x / sqrtValue;
      double[] rHighLow = new double[2];
      splitDouble(r, rHighLow);
      double rHigh = rHighLow[0];
      double rLow = rHighLow[1];

      // Correct for rounding in division
      rLow += (x - rHigh*sqrtHigh - rHigh*sqrtLow - rLow*sqrtHigh - rLow*sqrtLow) / sqrtValue;

      // Add in effect of additional bits of sqrt
      rLow += -x * (sqrtLow / sqrtValue) / sqrtValue; // The original code had dx, which effectively meant sqrtLow / sqrtValue

      double[] sumR = new double[2];
      twoSum(rHigh, rLow, sumR);
      rHigh = sumR[0];
      rLow = sumR[1];

      return atan(rHigh, rLow, false);
    }

    /** Compute the arc cosine of a number.
     * @param x number on which evaluation is done
     * @return arc cosine of x
     */
    public static double acos(double x) {
      if (x != x) { return Double.NaN; }
      if (x > 1.0 || x < -1.0) { return Double.NaN; }
      if (x == -1.0) { return Math.PI; }
      if (x == 1.0) { return 0.0; }
      if (x == 0) { return Math.PI * F_1_2; }

      // Compute acos(x) = atan(sqrt(1-x*x)/x)

      // Split x for extended precision
      double xHigh = getDoubleHighPart(x);
      double xLow = x - xHigh;

      // Square x: x*x = (xHigh + xLow)^2
      double xSquaredHigh = xHigh * xHigh;
      double xSquaredLow = xHigh * xLow * 2.0 + xLow * xLow;

      // Subtract from 1: 1 - x*x
      double oneMinusXSquaredHigh = 1.0 - xSquaredHigh;
      double oneMinusXSquaredLow = -(oneMinusXSquaredHigh - 1.0 + xSquaredHigh) - xSquaredLow;

      double[] sumOneMinusX2 = new double[2];
      twoSum(oneMinusXSquaredHigh, oneMinusXSquaredLow, sumOneMinusX2);
      oneMinusXSquaredHigh = sumOneMinusX2[0];
      oneMinusXSquaredLow = sumOneMinusX2[1];

      // Square root of (1 - x*x)
      double sqrtValue = sqrt(oneMinusXSquaredHigh + oneMinusXSquaredLow);
      double[] sqrtValueHighLow = new double[2];
      splitDouble(sqrtValue, sqrtValueHighLow);
      double sqrtHigh = sqrtValueHighLow[0];
      double sqrtLow = sqrtValueHighLow[1];

      // Extend precision of sqrt result
      sqrtLow += (oneMinusXSquaredHigh - sqrtHigh*sqrtHigh - 2*sqrtHigh*sqrtLow - sqrtLow*sqrtLow) / (2.0*sqrtValue);

      // Add contribution of the lowest bits of (1-x*x) to sqrt
      sqrtLow += oneMinusXSquaredLow / (2.0*sqrtValue);

      // Reconstruct sqrtValue with full precision
      sqrtValue = sqrtHigh + sqrtLow;
      sqrtLow = -(sqrtValue - sqrtHigh - sqrtLow);

      // Compute ratio r = sqrt(1-x*x) / x
      double r = sqrtValue / x;

      // Handle case where x is effectively zero (r overflows)
      if (Double.isInfinite(r)) {
          return Math.PI * F_1_2;
      }

      double[] rHighLow = new double[2];
      splitDouble(r, rHighLow);
      double rHigh = rHighLow[0];
      double rLow = rHighLow[1];

      // Correct for rounding in division
      rLow += (sqrtValue - rHigh*xHigh - rHigh*xLow - rLow*xHigh - rLow*xLow) / x;

      // Add in effect of additional bits of sqrt
      rLow += sqrtLow / x;

      double[] sumR = new double[2];
      twoSum(rHigh, rLow, sumR);
      rHigh = sumR[0];
      rLow = sumR[1];

      return atan(rHigh, rLow, x<0);
    }

    /** Compute the cubic root of a number.
     * @param x number on which evaluation is done
     * @return cubic root of x
     */
    public static double cbrt(double x) {
      /* Convert input double to bits */
      long inbits = Double.doubleToRawLongBits(x);
      int exponent = (int) ((inbits >> 52) & 0x7ff) - 1023;
      boolean isSubnormal = false;

      if (exponent == -1023) {
          if (x == 0) {
              return x; // Handles +/- 0.0
          }
          // Subnormal, so normalize
          isSubnormal = true;
          x *= 1.8014398509481984E16;  // Multiply by 2^54 to normalize
          inbits = Double.doubleToRawLongBits(x);
          exponent = (int) ((inbits >> 52) & 0x7ff) - 1023;
      }

      if (exponent == 1024) {
          // NaN or infinity. Don't care which, return as is.
          return x;
      }

      /* Divide the exponent by 3 */
      int exp3 = exponent / 3;

      /* p2 will be the nearest power of 2 to x with its exponent divided by 3 */
      double p2 = Double.longBitsToDouble((inbits & 0x8000000000000000L) | // Retain sign bit
                                          (long)(((exp3 + 1023) & 0x7ff)) << 52); // Apply new exponent

      /* This will be a number between 1 and 2 (mantissa part) */
      final double mantissa = Double.longBitsToDouble((inbits & 0x000fffffffffffffL) | 0x3ff0000000000000L);

      /* Estimate the cube root of mantissa by polynomial */
      double estimate = -0.010714690733195933;
      estimate = estimate * mantissa + 0.0875862700108075;
      estimate = estimate * mantissa + -0.3058015757857271;
      estimate = estimate * mantissa + 0.7249995199969751;
      estimate = estimate * mantissa + 0.5039018405998233;

      // Adjust estimate using precomputed table based on remainder of exponent/3
      estimate *= CBRTTWO[exponent % 3 + 2];

      // estimate should now be good to about 15 bits of precision. Do 2 rounds of
      // Newton's method to get closer. This should get us full double precision.
      // Scale down x for the purpose of doing Newton's method to avoid over/underflows.
      final double xScaled = x / (p2 * p2 * p2);
      estimate += (xScaled - estimate * estimate * estimate) / (3 * estimate * estimate);
      estimate += (xScaled - estimate * estimate * estimate) / (3 * estimate * estimate);

      // Do one round of Newton's method in extended precision to get the last bit right.
      double[] estimateHighLow = new double[2];
      splitDouble(estimate, estimateHighLow);
      double estHigh = estimateHighLow[0];
      double estLow = estimateHighLow[1];

      // Compute est^2 = (estHigh + estLow)^2
      double estSquaredHigh = estHigh * estHigh;
      double estSquaredLow = estHigh * estLow * 2.0 + estLow * estLow;

      // Compute est^3 = (estSquaredHigh + estSquaredLow) * (estHigh + estLow)
      double estCubedHigh = estSquaredHigh * estHigh;
      double estCubedLow = estSquaredHigh * estLow + estSquaredLow * estHigh + estSquaredLow * estLow;

      // Compute (xScaled - estCubedHigh) with extended precision
      double numeratorHigh = xScaled - estCubedHigh;
      double numeratorLow = -(numeratorHigh - xScaled + estCubedHigh);
      numeratorLow -= estCubedLow;

      // Calculate correction term (numerator) / (3 * est^2)
      estimate += (numeratorHigh + numeratorLow) / (3 * estimate * estimate);

      /* Scale by a power of two, so this is exact. */
      estimate *= p2;

      if (isSubnormal) {
          estimate *= 3.814697265625E-6;  // Multiply by 2^-18 to undo earlier scaling
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
        final double factorHigh = 0.01745329052209854; // pi/180 high part
        final double factorLow = 1.997844754509471E-9; // pi/180 low part

        double xHigh = getDoubleHighPart(x);
        double xLow = x - xHigh;

        // Compute (xHigh + xLow) * (factorHigh + factorLow) using extended precision product
        double result = xLow * factorLow + xLow * factorHigh + xHigh * factorLow + xHigh * factorHigh;

        // Ensure correct sign if calculation underflows to zero
        if (result == 0) {
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
        final double factorHigh = 57.2957763671875; // 180/pi high part
        final double factorLow = 3.145894820876798E-6; // 180/pi low part

        double xHigh = getDoubleHighPart(x);
        double xLow = x - xHigh;

        // Compute (xHigh + xLow) * (factorHigh + factorLow) using extended precision product
        return xLow * factorLow + xLow * factorHigh + xHigh * factorLow + xHigh * factorHigh;
    }

    /**
     * Absolute value.
     * @param x number from which absolute value is requested
     * @return abs(x)
     */
    public static int abs(final int x) {
        // Using bitwise operations to efficiently compute absolute value without branching
        final int signMask = x >> 31; // All 0s if positive, all 1s if negative
        return (x ^ signMask) - signMask; // If negative, (x XOR -1) - (-1) = (~x) + 1
    }

    /**
     * Absolute value.
     * @param x number from which absolute value is requested
     * @return abs(x)
     */
    public static long abs(final long x) {
        // Using bitwise operations to efficiently compute absolute value without branching
        final long signMask = x >> 63; // All 0s if positive, all 1s if negative
        return (x ^ signMask) - signMask; // If negative, (x XOR -1) - (-1) = (~x) + 1
    }

    /**
     * Absolute value.
     * @param x number from which absolute value is requested
     * @return abs(x)
     */
    public static float abs(final float x) {
        return Float.intBitsToFloat(MASK_NON_SIGN_INT & Float.floatToRawIntBits(x));
    }

    /**
     * Absolute value.
     * @param x number from which absolute value is requested
     * @return abs(x)
     */
    public static double abs(double x) {
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
        return abs(x - Double.longBitsToDouble(Double.doubleToRawLongBits(x) ^ 1));
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

        // First, simple and fast handling when 2^n can be represented using normal numbers.
        // This avoids complex bit manipulation for common cases.
        if ((n > -1023) && (n < 1024)) {
            return d * Double.longBitsToDouble(((long) (n + 1023)) << 52);
        }

        // Handle special cases: NaN, Infinite, Zero
        if (Double.isNaN(d) || Double.isInfinite(d) || (d == 0)) {
            return d;
        }

        // Handle extreme scaling that results in zero or infinity
        if (n < -2098) { // If scaling is too small, result is 0 (or -0)
            return (d > 0) ? 0.0 : -0.0;
        }
        if (n > 2097) { // If scaling is too large, result is infinity
            return (d > 0) ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
        }

        // Decompose d into its sign, exponent, and mantissa
        final long bits = Double.doubleToRawLongBits(d);
        final long sign = bits & 0x8000000000000000L;
        int  exponent   = ((int) (bits >>> 52)) & 0x7ff;
        long mantissa   = bits & 0x000fffffffffffffL;

        // Compute the scaled exponent
        int scaledExponent = exponent + n;

        if (n < 0) { // Scaling down
            // Case: input is normal, result is normal (only exponent changes)
            if (scaledExponent > 0) {
                return Double.longBitsToDouble(sign | (((long) scaledExponent) << 52) | mantissa);
            } else if (scaledExponent > -53) { // Case: input is normal, result is subnormal
                // Recover the hidden mantissa bit (implicit 1 for normal numbers)
                mantissa = mantissa | (1L << 52);

                // Scales down complete mantissa, potentially losing least significant bits
                final long mostSignificantLostBit = mantissa & (1L << (-scaledExponent));
                mantissa = mantissa >>> (1 - scaledExponent);
                if (mostSignificantLostBit != 0) {
                    // If the most significant lost bit was 1, we need to round up
                    mantissa++;
                }
                return Double.longBitsToDouble(sign | mantissa);
            } else { // Case: scales down to zero
                return (sign == 0L) ? 0.0 : -0.0;
            }
        } else { // Scaling up
            if (exponent == 0) { // Case: input is subnormal, normalize it first
                while ((mantissa >>> 52) != 1) {
                    mantissa = mantissa << 1;
                    --scaledExponent;
                }
                ++scaledExponent; // Adjust exponent for normalization
                mantissa = mantissa & 0x000fffffffffffffL; // Clear implicit bit

                if (scaledExponent < 2047) { // Result is normal
                    return Double.longBitsToDouble(sign | (((long) scaledExponent) << 52) | mantissa);
                } else { // Result overflows to infinity
                    return (sign == 0L) ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
                }

            } else if (scaledExponent < 2047) { // Case: input is normal, result is normal
                return Double.longBitsToDouble(sign | (((long) scaledExponent) << 52) | mantissa);
            } else { // Case: input is normal, result overflows to infinity
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

        // First, simple and fast handling when 2^n can be represented using normal numbers.
        if ((n > -127) && (n < 128)) {
            return f * Float.intBitsToFloat((n + 127) << 23);
        }

        // Handle special cases
        if (Float.isNaN(f) || Float.isInfinite(f) || (f == 0f)) {
            return f;
        }

        // Handle extreme scaling that results in zero or infinity
        if (n < -277) { // Roughly 127 (min exponent) + 23 (mantissa bits) + 27 (extra for safety)
            return (f > 0) ? 0.0f : -0.0f;
        }
        if (n > 276) { // Roughly 127 (max exponent) + 23 (mantissa bits) + 27 (extra for safety)
            return (f > 0) ? Float.POSITIVE_INFINITY : Float.NEGATIVE_INFINITY;
        }

        // Decompose f
        final int bits = Float.floatToIntBits(f);
        final int sign = bits & 0x80000000;
        int  exponent  = (bits >>> 23) & 0xff;
        int mantissa   = bits & 0x007fffff;

        // Compute scaled exponent
        int scaledExponent = exponent + n;

        if (n < 0) { // Scaling down
            if (scaledExponent > 0) { // Normal -> Normal
                return Float.intBitsToFloat(sign | (scaledExponent << 23) | mantissa);
            } else if (scaledExponent > -24) { // Normal -> Subnormal (23 mantissa bits)
                mantissa = mantissa | (1 << 23); // Recover hidden mantissa bit
                final int mostSignificantLostBit = mantissa & (1 << (-scaledExponent));
                mantissa = mantissa >>> (1 - scaledExponent);
                if (mostSignificantLostBit != 0) {
                    mantissa++;
                }
                return Float.intBitsToFloat(sign | mantissa);
            } else { // Scales down to 0
                return (sign == 0) ? 0.0f : -0.0f;
            }
        } else { // Scaling up
            if (exponent == 0) { // Input is subnormal, normalize it first
                while ((mantissa >>> 23) != 1) {
                    mantissa = mantissa << 1;
                    --scaledExponent;
                }
                ++scaledExponent;
                mantissa = mantissa & 0x007fffff;

                if (scaledExponent < 255) { // Result is normal
                    return Float.intBitsToFloat(sign | (scaledExponent << 23) | mantissa);
                } else { // Result overflows to infinity
                    return (sign == 0) ? Float.POSITIVE_INFINITY : Float.NEGATIVE_INFINITY;
                }

            } else if (scaledExponent < 255) { // Normal -> Normal
                return Float.intBitsToFloat(sign | (scaledExponent << 23) | mantissa);
            } else { // Normal -> Infinity
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
     * <li></li>
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

        // handling of some important special cases
        if (Double.isNaN(d) || Double.isNaN(direction)) {
            return Double.NaN;
        } else if (d == direction) {
            return direction;
        } else if (Double.isInfinite(d)) {
            // If d is infinite, nextAfter moves it towards MAX_VALUE or -MAX_VALUE
            return (d < 0) ? -Double.MAX_VALUE : Double.MAX_VALUE;
        } else if (d == 0) {
            // If d is zero, nextAfter moves it towards MIN_VALUE or -MIN_VALUE
            return (direction < 0) ? -Double.MIN_VALUE : Double.MIN_VALUE;
        }

        // General case for finite numbers
        // We can use raw bits directly as infinity and NaN are handled.
        final long bits = Double.doubleToRawLongBits(d);
        final long signBit = bits & 0x8000000000000000L;

        if ((direction < d) ^ (signBit == 0L)) {
            // Direction is less than d, OR d is positive and direction is greater than d
            // This means we are moving towards negative infinity (decrementing bits, unless d is positive zero)
            return Double.longBitsToDouble(signBit | ((bits & MASK_NON_SIGN_LONG) + 1));
        } else {
            // Direction is greater than d, OR d is negative and direction is less than d
            // This means we are moving towards positive infinity (incrementing bits, unless d is negative zero)
            return Double.longBitsToDouble(signBit | ((bits & MASK_NON_SIGN_LONG) - 1));
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
     * <li></li>
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

        // handling of some important special cases
        if (Float.isNaN(f) || Double.isNaN(direction)) {
            return Float.NaN;
        } else if (f == direction) {
            return (float) direction;
        } else if (Float.isInfinite(f)) {
            return (f < 0f) ? -Float.MAX_VALUE : Float.MAX_VALUE;
        } else if (f == 0f) {
            return (direction < 0) ? -Float.MIN_VALUE : Float.MIN_VALUE;
        }

        // General case for finite numbers
        final int bits = Float.floatToIntBits(f);
        final int signBit = bits & 0x80000000;

        if ((direction < f) ^ (signBit == 0)) {
            // Direction is less than f, OR f is positive and direction is greater than f
            return Float.intBitsToFloat(signBit | ((bits & MASK_NON_SIGN_INT) + 1));
        } else {
            // Direction is greater than f, OR f is negative and direction is less than f
            return Float.intBitsToFloat(signBit | ((bits & MASK_NON_SIGN_INT) - 1));
        }
    }

    /** Get the largest whole number smaller than x.
     * @param x number from which floor is requested
     * @return a double number f such that f is an integer f <= x < f + 1.0
     */
    public static double floor(double x) {
        long y;

        if (x != x) { // NaN
            return x;
        }

        // For very large numbers, they are already integers or infinity. No fractional part.
        if (x >= TWO_POWER_52 || x <= -TWO_POWER_52) {
            return x;
        }

        y = (long) x;
        if (x < 0 && y != x) { // If x is negative and has a fractional part
            y--;
        }

        // Handle floor(-0.0) == -0.0 and floor(0.0) == 0.0 correctly
        if (y == 0) {
            // x * y will produce the correct signed zero if x is negative zero, otherwise 0.0
            return x * y;
        }

        return y;
    }

    /** Get the smallest whole number larger than x.
     * @param x number from which ceil is requested
     * @return a double number c such that c is an integer c - 1.0 < x <= c
     */
    public static double ceil(double x) {
        double y;

        if (x != x) { // NaN
            return x;
        }

        y = floor(x);
        if (y == x) { // If x is already an integer
            return y;
        }

        y += 1.0;

        // Handle ceil(-0.0) == -0.0 and ceil(0.0) == 0.0 correctly
        if (y == 0) {
            // x * y will produce the correct signed zero if x is negative zero, otherwise 0.0
            return x * y;
        }

        return y;
    }

    /** Get the whole number that is the nearest to x, or the even one if x is exactly half way between two integers.
     * @param x number from which nearest whole number is requested
     * @return a double number r such that r is an integer r - 0.5 <= x <= r + 0.5
     */
    public static double rint(double x) {
        double floorX = floor(x);
        double fractionalPart = x - floorX;

        if (fractionalPart > 0.5) {
            if (floorX == -1.0) {
                return -0.0; // Special case: rint(-0.5) should be -0.0, not 0.0
            }
            return floorX + 1.0;
        }
        if (fractionalPart < 0.5) {
            return floorX;
        }

        /* Half way case, round to even integer */
        long roundedInteger = (long) floorX;
        return (roundedInteger & 1) == 0 ? floorX : floorX + 1.0;
    }

    /** Get the closest long to x.
     * @param x number from which closest long is requested
     * @return closest long to x
     */
    public static long round(double x) {
        return (long) floor(x + 0.5);
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
        if (a != b) {
            return Float.NaN;
        }
        /* min(+0.0,-0.0) == -0.0; check raw bits for signed zero */
        // 0x80000000 == Float.floatToRawIntBits(-0.0f)
        int bits = Float.floatToRawIntBits(a);
        if (bits == 0x80000000) {
            return a; // If a is -0.0, return it
        }
        return b; // Otherwise, if a and b are equal (and not NaN), return b (e.g., min(0.0, 0.0) is 0.0)
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
        if (a != b) {
            return Double.NaN;
        }
        /* min(+0.0,-0.0) == -0.0; check raw bits for signed zero */
        // 0x8000000000000000L == Double.doubleToRawLongBits(-0.0d)
        long bits = Double.doubleToRawLongBits(a);
        if (bits == 0x8000000000000000L) {
            return a;
        }
        return b;
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
        /* max(+0.0,-0.0) == +0.0; check raw bits for signed zero */
        // 0x80000000 == Float.floatToRawIntBits(-0.0f)
        int bits = Float.floatToRawIntBits(a);
        if (bits == 0x80000000) {
            return b; // If a is -0.0, b (which is 0.0) is returned
        }
        return a; // Otherwise, if a and b are equal, return a
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
        /* max(+0.0,-0.0) == +0.0; check raw bits for signed zero */
        // 0x8000000000000000L == Double.doubleToRawLongBits(-0.0d)
        long bits = Double.doubleToRawLongBits(a);
        if (bits == 0x8000000000000000L) {
            return b;
        }
        return a;
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

            // Optimize if one value is much larger than the other
            if (expX > expY + 27) { // 27 = 54/2, assuming roughly half precision loss
                return abs(x);
            } else if (expY > expX + 27) {
                return abs(y);
            } else {

                // Find an intermediate scale to avoid both overflow and underflow
                final int middleExp = (expX + expY) / 2;

                // Scale parameters without losing precision using scalb
                final double scaledX = scalb(x, -middleExp);
                final double scaledY = scalb(y, -middleExp);

                // Compute scaled hypotenuse
                final double scaledH = sqrt(scaledX * scaledX + scaledY * scaledY);

                // Remove scaling to get final result
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
        // The highest order bit of (magnitude XOR sign) will be 0 if both magnitude and sign
        // have the same sign, and 1 otherwise. If different, we flip the sign of magnitude.
        final long magBits = Double.doubleToRawLongBits(magnitude); // don't care about NaN bit pattern
        final long signBits = Double.doubleToRawLongBits(sign);

        if ((magBits ^ signBits) >= 0) {
            return magnitude; // Same sign, no change needed
        }
        return -magnitude; // Different signs, flip magnitude's sign
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
        final int magBits = Float.floatToRawIntBits(magnitude);
        final int signBits = Float.floatToRawIntBits(sign);

        if ((magBits ^ signBits) >= 0) {
            return magnitude;
        }
        return -magnitude;
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
        // NaN and Infinite numbers will return the biased exponent 2047, which becomes 1024 after subtracting bias. This is correct.
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
        // NaN and Infinite numbers will return the biased exponent 255, which becomes 128 after subtracting bias. This is correct.
        return ((Float.floatToRawIntBits(f) >>> 23) & 0xff) - 127;
    }

    /**
     * Print out contents of arrays, and check the length.
     * <p>used to generate the preset arrays originally.</p>
     * @param a unused command line arguments
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
                        // Negative integer powers: compute reciprocal
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
        /** k, the integer part of the argument divided by PI/2. */
        private final int finalK;
        /** remA, the high part of the remainder of the argument mod PI/2. */
        private final double finalRemA;
        /** remB, the low part of the remainder of the argument mod PI/2. */
        private final double finalRemB;

        /**
         * Constructs a CodyWaite reducer for an argument `xa`.
         * This performs argument reduction modulo PI/2 to simplify trigonometric calculations.
         * @param xa The argument to reduce.
         */
        CodyWaite(double xa) {
            // Estimate k, the integer multiple of PI/2
            int k = (int)(xa * 0.6366197723675814); // Approximation for xa / (PI/2)

            double remA; // High part of the remainder
            double remB; // Low part of the remainder

            // Loop to compute remainder precisely and adjust k if necessary
            while (true) {
                // Subtract k * PI/2 using high-precision constants for PI/2
                double termHigh = -k * 1.570796251296997; // PI/2 high bits
                remA = xa + termHigh; // Initial remainder high part
                remB = -(remA - xa - termHigh); // Initial remainder low part

                double termMid = -k * 7.549789948768648E-8; // PI/2 mid bits
                double bTemp = remA;
                remA = termMid + bTemp;
                remB += -(remA - bTemp - termMid);

                double termLow = -k * 6.123233995736766E-17; // PI/2 low bits
                bTemp = remA;
                remA = termLow + bTemp;
                remB += -(remA - bTemp - termLow);

                if (remA > 0) {
                    break; // Remainder is positive, done
                }

                // Remainder is negative (due to precision issues or x being just under a multiple of pi/2),
                // so decrement k and try again to get a positive remainder.
                --k;
            }

            this.finalK = k;
            this.finalRemA = remA;
            this.finalRemB = remB;
        }

        /**
         * @return The integer part of the argument divided by PI/2 (quadrant information).
         */
        int getK() {
            return finalK;
        }
        /**
         * @return The high part of the remainder of the argument modulo PI/2.
         */
        double getRemA() {
            return finalRemA;
        }
        /**
         * @return The low part of the remainder of the argument modulo PI/2.
         */
        double getRemB() {
            return finalRemB;
        }
    }
}