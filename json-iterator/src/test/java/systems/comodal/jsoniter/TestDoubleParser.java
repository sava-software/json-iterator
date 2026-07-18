package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;

import static java.lang.Double.doubleToLongBits;
import static java.lang.Float.floatToIntBits;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Direct DoubleParser coverage for behavior the JsonIterator token scanner
/// cannot reach: raw grammar rejections the scanner would never tokenize
/// (e.g. "12x3"), exact-length buffers where a mutated bounds check trips an
/// ArrayIndexOutOfBoundsException instead of the contractual
/// NumberFormatException, and Eisel-Lemire rounding boundaries pinned
/// bit-exact against the JDK reference parsers.
///
/// Inputs that end immediately after a sign or exponent marker ("-", "1e",
/// "1e+", "1e+x") are deliberately absent: they terminate in a slow-path
/// return whose only reachable behavior is the reference parser's throw, so
/// covering them would only surface unkillable return-value mutants.
final class TestDoubleParser {

  private static void assertParses(final String str) {
    final char[] buf = str.toCharArray();
    final long expected = doubleToLongBits(Double.parseDouble(str));
    assertEquals(expected, doubleToLongBits(DoubleParser.parse(buf, 0, buf.length)), str);
    final int expectedFloat = floatToIntBits(Float.parseFloat(str));
    assertEquals(expectedFloat, floatToIntBits(DoubleParser.parseFloat(buf, 0, buf.length)), str);
    // The same token as an interior window of a larger buffer.
    final char[] padded = new char[buf.length + 3];
    padded[0] = 'x';
    padded[1] = 'x';
    System.arraycopy(buf, 0, padded, 2, buf.length);
    padded[padded.length - 1] = 'y';
    assertEquals(expected, doubleToLongBits(DoubleParser.parse(padded, 2, buf.length)), str);
    assertEquals(expectedFloat, floatToIntBits(DoubleParser.parseFloat(padded, 2, buf.length)), str);
  }

  private static void assertRejects(final String str) {
    final char[] buf = str.toCharArray();
    assertThrows(NumberFormatException.class, () -> DoubleParser.parse(buf, 0, buf.length), str);
    assertThrows(NumberFormatException.class, () -> DoubleParser.parseFloat(buf, 0, buf.length), str);
  }

  @Test
  void test_empty_input() {
    assertRejects("");
    // An empty window at the end of a non-empty buffer: a mutated bounds
    // check would read past the window instead of throwing.
    assertThrows(NumberFormatException.class, () -> DoubleParser.parse(new char[]{'1'}, 1, 0));
  }

  @Test
  void test_raw_grammar_rejections() {
    // Rejections must fall through to the reference parser's
    // NumberFormatException, never to a fast-path misparse.
    assertRejects(":");
    assertRejects("1a");
    assertRejects("1+");
    assertRejects("1.a");
    assertRejects("12x3");
    assertRejects("1e5x");
    assertRejects("1e5+");
  }

  @Test
  void test_legacy_grammar_via_slow_path() {
    // Accepted by Double.parseDouble but outside the fast grammar.
    assertParses("+1");
    assertParses("1. ");
    assertParses("1.5");
    assertParses("12.25");
  }

  @Test
  void test_exponent_clamp() {
    // Exponent digit accumulation clamps instead of overflowing long; an
    // unclamped accumulator wraps to a negative exponent and flips the
    // saturation direction.
    assertParses("1e9223372036854775808");
    assertParses("-1e9223372036854775808");
    assertParses("1e-9223372036854775808");
    assertParses("1e99999999999999999999");
    assertParses("1e-99999999999999999999");
  }

  @Test
  void test_double_saturation_boundaries() {
    // exp10 == -342: a 19-digit significand is still a nonzero subnormal.
    assertParses("9999999999999999999e-342");
    assertParses("-9999999999999999999e-342");
    // exp10 == 308 must stay finite; larger exponents saturate.
    assertParses("1e308");
    assertParses("-1e308");
    assertParses("2e308");
    // exp2 == MIN - 64 exactly: the subnormal shift must zero, not no-op.
    assertParses("2e-327");
    assertParses("-2e-327");
    assertParses("17628e-331");
    // Sweep the subnormal-to-zero transition.
    for (int d = 1; d <= 9; ++d) {
      for (int e = -345; e <= -300; ++e) {
        assertParses(d + "e" + e);
      }
    }
  }

  @Test
  void test_double_rounding_boundaries() {
    // 128-bit product carry propagation.
    assertParses("1e210");
    assertParses("-1e210");
    assertParses("2e210");
    // Round-to-even ties at the exp10 range boundaries of the halfway check:
    // (2^53 +/- 1) * 5^4 * 10^-4.
    assertParses("5629499534213120625e-4");
    assertParses("5629499534213125625e-4");
    assertParses("1e23");
    assertParses("-1e23");
    // Values just above a tie must not be rounded as ties.
    assertParses("9223372036854776833");
    assertParses("9223372036854775809e23");
    // Rounding overflow into the next binary exponent.
    assertParses("1.9999999999999999");
    assertParses("3.9999999999999999");
    assertParses("1125899906842623999e-3");
    // 2^53 neighborhood.
    assertParses("9007199254740991");
    assertParses("9007199254740992");
    assertParses("9007199254740993");
  }

  @Test
  void test_float_boundaries() {
    // abs(exp10) == 11 is just past the binary32 Clinger table.
    assertParses("1e11");
    assertParses("-1e11");
    assertParses("1e-11");
    assertParses("3e-11");
    // A value just under a float tie: the tie check must require an exact
    // product (low product word > 1 forbids the round-to-even adjustment).
    assertParses("9223372586610589697");
    // Low product word exactly zero with all-ones ambiguity bits: the 128-bit
    // refinement's carry must be taken exactly when it occurs.
    assertParses("9223372586610589694");
    assertParses("9223372586610589695");
    assertParses("9223373686122217470");
    assertParses("9223375885145473022");
    // A genuine float halfway needing the 128-bit refinement.
    assertParses("84065015e-1");
    assertParses("8406501.5");
    // Rounding overflow into the next binary exponent (2^25 - 1 rounds up).
    assertParses("33554431");
    assertParses("33554430");
    assertParses("33554433");
    // 2^24 neighborhood.
    assertParses("16777215");
    assertParses("16777216");
    assertParses("16777217");
  }
}
