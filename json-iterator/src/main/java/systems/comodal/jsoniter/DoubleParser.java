package systems.comodal.jsoniter;

import static java.lang.Double.longBitsToDouble;
import static java.lang.Float.intBitsToFloat;
import static java.lang.Long.compareUnsigned;
import static java.lang.Long.numberOfLeadingZeros;
import static java.lang.Math.abs;
import static java.lang.Math.unsignedMultiplyHigh;
import static systems.comodal.jsoniter.PowersOfFive.MIN_POWER_OF_FIVE;
import static systems.comodal.jsoniter.PowersOfFive.POWERS_OF_FIVE;

/// Allocation-free decimal to binary64/binary32 conversion: the Clinger fast
/// path for small exact values, otherwise the Eisel-Lemire algorithm described
/// by Daniel Lemire in "Number Parsing at a Gigabyte per Second"
/// (https://arxiv.org/abs/2101.11408). Per Mushtak & Lemire, "Fast Number
/// Parsing Without Fallback" (https://arxiv.org/abs/2212.06644), the
/// algorithm's product is always sufficiently accurate, so no correctness
/// fallback is required for the covered grammar.
///
/// Floats are parsed with binary32-parameterized arithmetic rather than by
/// narrowing a parsed double, which would double-round: a decimal close to a
/// binary32 rounding boundary can round onto the midpoint in binary64 and
/// then round the wrong way when narrowed.
///
/// Inputs outside the fast grammar — more than 19 significant digits,
/// `Infinity`/`NaN`, leading `+` or `.`, hex floats, `f`/`d` suffixes,
/// surrounding whitespace — defer to [Double#parseDouble(String)] /
/// [Float#parseFloat(String)], preserving their exact semantics at the cost
/// of one String.
///
/// The Eisel-Lemire implementation is adapted from simdjson-java
/// (https://github.com/simdjson/simdjson-java, Apache License 2.0).
final class DoubleParser {

  // A long treated as unsigned safely accommodates 19 digits (9999999999999999999 < 2^64).
  private static final int FAST_PATH_MAX_DIGIT_COUNT = 19;

  // For q < -342, w * 10^q with w < 10^19 is below 2^-1075 (half of the smallest
  // subnormal, 2^-1074) and therefore rounds to zero.
  private static final int DOUBLE_MIN_POWER_OF_TEN = -342;
  // For q > 308, w * 10^q with w >= 1 exceeds (1 - 2^-53) * 2^1024 and is therefore infinite.
  private static final int DOUBLE_MAX_POWER_OF_TEN = 308;
  private static final double[] DOUBLE_POWERS_OF_TEN = {
      1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11,
      1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18, 1e19, 1e20, 1e21, 1e22
  };
  private static final long MAX_LONG_REPRESENTED_AS_DOUBLE_EXACTLY = (1L << 53) - 1;
  private static final int IEEE64_EXPONENT_BIAS = 1023;
  private static final int IEEE64_SIGN_BIT_INDEX = 63;
  private static final int IEEE64_SIGNIFICAND_EXPLICIT_BIT_COUNT = 52;
  private static final int IEEE64_SIGNIFICAND_SIZE_IN_BITS = IEEE64_SIGNIFICAND_EXPLICIT_BIT_COUNT + 1;
  private static final int IEEE64_MAX_FINITE_NUMBER_EXPONENT = 1023;
  private static final int IEEE64_MIN_FINITE_NUMBER_EXPONENT = -1022;
  private static final int IEEE64_SUBNORMAL_EXPONENT = -1023;
  private static final long DOUBLE_MULTIPLICATION_MASK = 0xFFFFFFFFFFFFFFFFL >>> (IEEE64_SIGNIFICAND_EXPLICIT_BIT_COUNT + 3);

  // For q < -65, w * 10^q with w < 10^19 is below 10^-46 < 2^-150 (half of the
  // smallest subnormal, 2^-149) and therefore rounds to zero. Note that this
  // cutoff must NOT be -63: at q = -64 a 19-digit significand reaches ~9.99e-46,
  // which is above 2^-150 and rounds to Float.MIN_VALUE, handled by the
  // Eisel-Lemire subnormal path (upstream fast_float#167).
  private static final int FLOAT_MIN_POWER_OF_TEN = -65;
  // For q > 38, w * 10^q with w >= 1 exceeds (1 - 2^-24) * 2^128 and is therefore infinite.
  private static final int FLOAT_MAX_POWER_OF_TEN = 38;
  private static final float[] FLOAT_POWERS_OF_TEN = {
      1e0f, 1e1f, 1e2f, 1e3f, 1e4f, 1e5f, 1e6f, 1e7f, 1e8f, 1e9f, 1e10f
  };
  private static final long MAX_LONG_REPRESENTED_AS_FLOAT_EXACTLY = (1L << 24) - 1;
  private static final int IEEE32_EXPONENT_BIAS = 127;
  private static final int IEEE32_SIGN_BIT_INDEX = 31;
  private static final int IEEE32_SIGNIFICAND_EXPLICIT_BIT_COUNT = 23;
  private static final int IEEE32_SIGNIFICAND_SIZE_IN_BITS = IEEE32_SIGNIFICAND_EXPLICIT_BIT_COUNT + 1;
  private static final int IEEE32_MAX_FINITE_NUMBER_EXPONENT = 127;
  private static final int IEEE32_MIN_FINITE_NUMBER_EXPONENT = -126;
  private static final int IEEE32_SUBNORMAL_EXPONENT = -127;
  private static final long FLOAT_MULTIPLICATION_MASK = 0xFFFFFFFFFFFFFFFFL >>> (IEEE32_SIGNIFICAND_EXPLICIT_BIT_COUNT + 3);

  private DoubleParser() {
  }

  /// Parses the decimal number in `buf[offset, offset + len)`, matching
  /// [Double#parseDouble(String)] exactly, including thrown
  /// NumberFormatExceptions.
  static double parse(final char[] buf, final int offset, final int len) {
    return parse(buf, offset, len, false);
  }

  /// Parses the decimal number in `buf[offset, offset + len)`, matching
  /// [Float#parseFloat(String)] exactly, including thrown
  /// NumberFormatExceptions.
  static float parseFloat(final char[] buf, final int offset, final int len) {
    return (float) parse(buf, offset, len, true);
  }

  private static double parse(final char[] buf, final int offset, final int len, final boolean asFloat) {
    final int to = offset + len;
    int i = offset;
    if (i >= to) {
      throw new NumberFormatException("empty String");
    }
    final boolean negative = buf[i] == '-';
    if (negative) {
      ++i;
    }
    if (i >= to) {
      return slow(buf, offset, len, asFloat);
    }
    char c = buf[i];
    if (c < '0' || c > '9') {
      // Infinity, NaN, leading '+' or '.', hex, whitespace: legacy grammar.
      return slow(buf, offset, len, asFloat);
    }
    final int digitsStart = i;
    long digits = 0;
    do {
      digits = digits * 10 + (c - '0');
      if (++i == to) {
        break;
      }
      c = buf[i];
    } while (c >= '0' && c <= '9');
    int digitCount = i - digitsStart;
    long exp10 = 0;
    if (i < to && buf[i] == '.') {
      ++i;
      final int fractionStart = i;
      while (i < to && (c = buf[i]) >= '0' && c <= '9') {
        digits = digits * 10 + (c - '0');
        ++i;
      }
      final int fractionCount = i - fractionStart;
      exp10 = -fractionCount;
      digitCount += fractionCount;
    }
    if (i < to && (buf[i] == 'e' || buf[i] == 'E')) {
      if (++i == to) {
        return slow(buf, offset, len, asFloat); // e.g. "1e": legacy exception message
      }
      c = buf[i];
      final boolean negativeExponent = c == '-';
      if (negativeExponent || c == '+') {
        if (++i == to) {
          return slow(buf, offset, len, asFloat);
        }
        c = buf[i];
      }
      if (c < '0' || c > '9') {
        return slow(buf, offset, len, asFloat);
      }
      long exponent = 0;
      do {
        if (exponent < 0x1000_0000) { // clamp: anything larger saturates to zero/infinity anyway
          exponent = exponent * 10 + (c - '0');
        }
        if (++i == to) {
          break;
        }
        c = buf[i];
      } while (c >= '0' && c <= '9');
      exp10 += negativeExponent ? -exponent : exponent;
    }
    if (i != to) {
      // Trailing content such as an 'f'/'d' suffix or whitespace: legacy grammar.
      return slow(buf, offset, len, asFloat);
    }
    if (digitCount > FAST_PATH_MAX_DIGIT_COUNT) {
      // The digit accumulator may have overflowed; leading zeros could still
      // make this exact, but such inputs are rare enough to defer entirely.
      return slow(buf, offset, len, asFloat);
    }
    return asFloat
        ? computeFloat(negative, digits, exp10)
        : computeDouble(negative, digits, exp10);
  }

  private static double slow(final char[] buf, final int offset, final int len, final boolean asFloat) {
    final var str = new String(buf, offset, len);
    return asFloat ? Float.parseFloat(str) : Double.parseDouble(str);
  }

  private static double computeDouble(final boolean negative, long significand10, final long exp10) {
    if (abs(exp10) < DOUBLE_POWERS_OF_TEN.length && compareUnsigned(significand10, MAX_LONG_REPRESENTED_AS_DOUBLE_EXACTLY) <= 0) {
      // https://www.exploringbinary.com/fast-path-decimal-to-floating-point-conversion/
      double result = significand10;
      if (exp10 < 0) {
        result = result / DOUBLE_POWERS_OF_TEN[(int) -exp10];
      } else {
        result = result * DOUBLE_POWERS_OF_TEN[(int) exp10];
      }
      return negative ? -result : result;
    }

    if (exp10 < DOUBLE_MIN_POWER_OF_TEN || significand10 == 0) {
      return negative ? -0.0 : 0.0;
    } else if (exp10 > DOUBLE_MAX_POWER_OF_TEN) {
      return negative ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
    }

    // Normalize the decimal significand into [2^63, 2^64).
    final int lz = numberOfLeadingZeros(significand10);
    significand10 <<= lz;

    // Compute w * 5^q as a 128-bit product, extending to the second 64 bits of
    // the power only when the truncated product could be ambiguous.
    final int powersOfFiveTableIndex = 2 * (int) (exp10 - MIN_POWER_OF_FIVE);
    long upper = unsignedMultiplyHigh(significand10, POWERS_OF_FIVE[powersOfFiveTableIndex]);
    long lower = significand10 * POWERS_OF_FIVE[powersOfFiveTableIndex];
    if ((upper & DOUBLE_MULTIPLICATION_MASK) == DOUBLE_MULTIPLICATION_MASK) {
      final long secondUpper = unsignedMultiplyHigh(significand10, POWERS_OF_FIVE[powersOfFiveTableIndex + 1]);
      lower += secondUpper;
      if (compareUnsigned(secondUpper, lower) > 0) {
        upper++;
      }
      // Per Mushtak & Lemire, the product is now sufficiently accurate.
    }

    // Extract 54 bits: 53 for the binary64 significand plus one rounding bit.
    final long upperBit = upper >>> 63;
    final long upperShift = upperBit + 9;
    long significand2 = upper >>> upperShift;

    // The binary exponent: (217706 * q) >> 16 approximates q * log(10) / log(2)
    // exactly within the supported range, adjusted for both normalizations.
    long exp2 = ((217706 * exp10) >> 16) + 63 - lz + upperBit;
    if (exp2 < IEEE64_MIN_FINITE_NUMBER_EXPONENT) {
      if (exp2 <= IEEE64_MIN_FINITE_NUMBER_EXPONENT - 64) {
        return negative ? -0.0 : 0.0;
      }
      // Likely subnormal: shift into the subnormal representation.
      significand2 >>= 1 - IEEE64_EXPONENT_BIAS - exp2;
      significand2 += significand2 & 1;
      significand2 >>= 1;
      // A subnormal may round up into the smallest normal number.
      exp2 = (significand2 < (1L << IEEE64_SIGNIFICAND_EXPLICIT_BIT_COUNT)) ? IEEE64_SUBNORMAL_EXPONENT : IEEE64_MIN_FINITE_NUMBER_EXPONENT;
      return toDouble(negative, significand2, exp2);
    }

    // Round-to-even when the value falls exactly halfway; see sections 6, 8.1,
    // and 9.1 of "Number Parsing at a Gigabyte per Second".
    if (exp10 >= -4 && exp10 <= 23) {
      if ((significand2 << upperShift == upper) && (compareUnsigned(lower, 1) <= 0)) {
        if ((significand2 & 3) == 1) {
          significand2 &= ~1;
        }
      }
    }

    significand2 += significand2 & 1;
    significand2 >>= 1;

    if (significand2 == (1L << IEEE64_SIGNIFICAND_SIZE_IN_BITS)) {
      // Rounding overflowed the significand.
      significand2 >>= 1;
      exp2++;
    }

    if (exp2 > IEEE64_MAX_FINITE_NUMBER_EXPONENT) {
      return negative ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
    }
    return toDouble(negative, significand2, exp2);
  }

  private static double toDouble(final boolean negative, final long significand2, final long exp2) {
    long bits = significand2;
    bits &= ~(1L << IEEE64_SIGNIFICAND_EXPLICIT_BIT_COUNT); // clear the implicit bit
    bits |= (exp2 + IEEE64_EXPONENT_BIAS) << IEEE64_SIGNIFICAND_EXPLICIT_BIT_COUNT;
    return longBitsToDouble(negative ? bits | (1L << IEEE64_SIGN_BIT_INDEX) : bits);
  }

  /// The binary32 parameterization of [#computeDouble(boolean, long, long)];
  /// the structure and comments there apply, with 25 bits extracted (24 plus a
  /// rounding bit, so a right shift of 38 or 39) and binary32 bounds.
  private static float computeFloat(final boolean negative, long significand10, final long exp10) {
    if (abs(exp10) < FLOAT_POWERS_OF_TEN.length && compareUnsigned(significand10, MAX_LONG_REPRESENTED_AS_FLOAT_EXACTLY) <= 0) {
      float result = significand10;
      if (exp10 < 0) {
        result = result / FLOAT_POWERS_OF_TEN[(int) -exp10];
      } else {
        result = result * FLOAT_POWERS_OF_TEN[(int) exp10];
      }
      return negative ? -result : result;
    }

    if (exp10 < FLOAT_MIN_POWER_OF_TEN || significand10 == 0) {
      return negative ? -0.0f : 0.0f;
    } else if (exp10 > FLOAT_MAX_POWER_OF_TEN) {
      return negative ? Float.NEGATIVE_INFINITY : Float.POSITIVE_INFINITY;
    }

    final int lz = numberOfLeadingZeros(significand10);
    significand10 <<= lz;

    final int powersOfFiveTableIndex = 2 * (int) (exp10 - MIN_POWER_OF_FIVE);
    long upper = unsignedMultiplyHigh(significand10, POWERS_OF_FIVE[powersOfFiveTableIndex]);
    long lower = significand10 * POWERS_OF_FIVE[powersOfFiveTableIndex];
    if ((upper & FLOAT_MULTIPLICATION_MASK) == FLOAT_MULTIPLICATION_MASK) {
      final long secondUpper = unsignedMultiplyHigh(significand10, POWERS_OF_FIVE[powersOfFiveTableIndex + 1]);
      lower += secondUpper;
      if (compareUnsigned(secondUpper, lower) > 0) {
        upper++;
      }
    }

    final long upperBit = upper >>> 63;
    final long upperShift = upperBit + 38;
    long significand2 = upper >>> upperShift;

    long exp2 = ((217706 * exp10) >> 16) + 63 - lz + upperBit;
    if (exp2 < IEEE32_MIN_FINITE_NUMBER_EXPONENT) {
      if (exp2 <= IEEE32_MIN_FINITE_NUMBER_EXPONENT - 64) {
        return negative ? -0.0f : 0.0f;
      }
      significand2 >>= 1 - IEEE32_EXPONENT_BIAS - exp2;
      significand2 += significand2 & 1;
      significand2 >>= 1;
      exp2 = (significand2 < (1L << IEEE32_SIGNIFICAND_EXPLICIT_BIT_COUNT)) ? IEEE32_SUBNORMAL_EXPONENT : IEEE32_MIN_FINITE_NUMBER_EXPONENT;
      return toFloat(negative, (int) significand2, (int) exp2);
    }

    if (exp10 >= -17 && exp10 <= 10) {
      if ((significand2 << upperShift == upper) && (compareUnsigned(lower, 1) <= 0)) {
        if ((significand2 & 3) == 1) {
          significand2 &= ~1;
        }
      }
    }

    significand2 += significand2 & 1;
    significand2 >>= 1;

    if (significand2 == (1L << IEEE32_SIGNIFICAND_SIZE_IN_BITS)) {
      significand2 >>= 1;
      exp2++;
    }

    if (exp2 > IEEE32_MAX_FINITE_NUMBER_EXPONENT) {
      return negative ? Float.NEGATIVE_INFINITY : Float.POSITIVE_INFINITY;
    }
    return toFloat(negative, (int) significand2, (int) exp2);
  }

  private static float toFloat(final boolean negative, final int significand2, final int exp2) {
    int bits = significand2;
    bits &= ~(1 << IEEE32_SIGNIFICAND_EXPLICIT_BIT_COUNT); // clear the implicit bit
    bits |= (exp2 + IEEE32_EXPONENT_BIAS) << IEEE32_SIGNIFICAND_EXPLICIT_BIT_COUNT;
    return intBitsToFloat(negative ? bits | (1 << IEEE32_SIGN_BIT_INDEX) : bits);
  }
}
