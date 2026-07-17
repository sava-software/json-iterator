package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.FieldSource;
import systems.comodal.jsoniter.factories.JsonIteratorFactory;

import java.util.Random;

import static java.lang.Double.doubleToLongBits;
import static java.lang.Float.floatToIntBits;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ParameterizedClass
@FieldSource("systems.comodal.jsoniter.TestFactories#FACTORIES")
final class TestDouble {

  private final JsonIteratorFactory factory;

  TestDouble(final JsonIteratorFactory factory) {
    this.factory = factory;
  }

  private void assertParses(final String number) {
    final long expected = doubleToLongBits(Double.parseDouble(number));
    assertEquals(expected, doubleToLongBits(factory.create(number).readDouble()), number);
    assertEquals(expected, doubleToLongBits(factory.create('"' + number + '"').readDouble()), number);
    final int expectedFloat = floatToIntBits(Float.parseFloat(number));
    assertEquals(expectedFloat, floatToIntBits(factory.create(number).readFloat()), number);
    assertEquals(expectedFloat, floatToIntBits(factory.create('"' + number + '"').readFloat()), number);
  }

  @Test
  void test_edge_cases() {
    final var edges = new String[]{
        "0", "0.0", "-0.0", "1", "-1", "0.5", "1e0", "1E0", "123.", "0.25",
        // subnormal/normal boundary
        "2.2250738585072011e-308", "2.2250738585072013e-308", "2.2250738585072014e-308",
        // MIN_VALUE and below
        "4.9e-324", "4.9406564584124654e-324", "2.4703282292062327e-324",
        "1e-324", "-1e-324", "2e-324", "3e-324",
        // MAX_VALUE and overflow
        "1.7976931348623157e308", "-1.7976931348623157e308",
        "1.7976931348623159e308", "1e309", "-1e309", "1e999", "1e-999",
        // 2^53 significand boundary and 19-20 digit significands
        "9007199254740992", "9007199254740993", "9007199254740994",
        "9223372036854775807", "9999999999999999999", "18446744073709551615",
        // slow-path fallbacks
        "123456789012345678901234567890.123456789",
        "0.00000000000000000000000000000000000001",
        "1.00000000000000000000000000000000000001",
        // assorted
        "0.000001", "1e-7", "12345678901234567890e-10", "1e22", "1e23", "-1e22",
        "3.141592653589793", "2.718281828459045", "1.7976931348623157e+308",
        "8.37377E9", "123e-5", "0.087"
    };
    for (final var edge : edges) {
      assertParses(edge);
    }
  }

  @Test
  void test_space_terminates_number_token() {
    // fuzz regression: the chars source slices its token window as
    // [head - len, head), so a space swallowed mid-scan shifted the window
    // and dropped leading digits — "9007199254740993993 " read as
    // 7.199254740993993E15. A space now terminates the token on both sources.
    assertEquals(
        doubleToLongBits(9.007199254740994E18),
        doubleToLongBits(factory.create("9007199254740993993 ").readDouble()));
    assertEquals("9007199254740993993", factory.create("9007199254740993993 ").readNumberAsString());
    for (final var padded : new String[]{"12.5 ", "12.5  ", "12.5 ,", "12.5 }", "12.5 ]"}) {
      assertEquals(12.5d, factory.create(padded).readDouble(), padded);
    }
    // an interior space no longer splices "1 2" into 12
    assertEquals(1.0d, factory.create("1 2").readDouble());
    assertEquals("1", factory.create("1 2").readNumberAsString());
    var ji = factory.create("[1 , 2.5 ]");
    assertTrue(ji.readArray());
    assertEquals(1.0d, ji.readDouble());
    assertTrue(ji.readArray());
    assertEquals(2.5d, ji.readDouble());
    assertFalse(ji.readArray());
  }

  @Test
  void test_infinity_and_nan_strings() {
    assertEquals(Double.POSITIVE_INFINITY, factory.create("\"Infinity\"").readDouble());
    assertEquals(Double.POSITIVE_INFINITY, factory.create("\"+Infinity\"").readDouble());
    assertEquals(Double.NEGATIVE_INFINITY, factory.create("\"-Infinity\"").readDouble());
    assertEquals(doubleToLongBits(Double.NaN), doubleToLongBits(factory.create("\"NaN\"").readDouble()));
    // legacy Double.parseDouble grammar accepted via quoted strings
    assertEquals(1.5d, factory.create("\"1.5f\"").readDouble());
    assertEquals(1.5d, factory.create("\"1.5d\"").readDouble());
    assertEquals(1.5d, factory.create("\" 1.5 \"").readDouble());
    assertEquals(0.5d, factory.create("\".5\"").readDouble());
    assertEquals(3.0d, factory.create("\"0x1.8p1\"").readDouble());
    assertThrows(JsonException.class, () -> factory.create("\"\"").readDouble());
    assertThrows(JsonException.class, () -> factory.create("\"12x\"").readDouble());
    assertThrows(JsonException.class, () -> factory.create("\"1e\"").readDouble());
  }

  @Test
  void test_float_edge_cases() {
    final var edges = new String[]{
        // MIN_VALUE neighborhood and the zero/MIN_VALUE rounding boundary 2^-150
        "1.4e-45", "1.401298464324817e-45", "7.006492321624085e-46", "7.1e-46", "7e-46", "6.9e-46",
        "9e-46", "1e-46", "-1e-46",
        // the q = -64 cutoff case simdjson-java's -63 bound gets wrong
        "9999999999999999999e-64", "9999999999999999999e-65",
        // subnormal/normal boundary
        "1.17549435e-38", "1.1754942e-38", "1.17549433e-38",
        // MAX_VALUE and overflow
        "3.4028235e38", "3.4028236e38", "3.5e38", "-3.5e38", "1e39",
        // 2^24 significand boundary
        "16777215", "16777216", "16777217", "16777218", "16777219",
        // values where double-rounding through binary64 could bite
        "1.00000017881393432617187499", "1.000000178813934326171875", "1.00000017881393432617187501",
        "7.038531e-26", "0.000000000000000000000000070385313"
    };
    for (final var edge : edges) {
      final int expected = floatToIntBits(Float.parseFloat(edge));
      assertEquals(expected, floatToIntBits(factory.create(edge).readFloat()), edge);
      assertEquals(expected, floatToIntBits(factory.create('"' + edge + '"').readFloat()), edge);
    }
    assertEquals(Float.POSITIVE_INFINITY, factory.create("\"Infinity\"").readFloat());
    assertEquals(Float.NEGATIVE_INFINITY, factory.create("\"-Infinity\"").readFloat());
    assertEquals(floatToIntBits(Float.NaN), floatToIntBits(factory.create("\"NaN\"").readFloat()));
  }

  @Test
  void test_float_random_round_trip() {
    final long seed = new Random().nextLong();
    final var random = new Random(seed);
    for (int i = 0; i < 20_000; ++i) {
      final float value = Float.intBitsToFloat(random.nextInt());
      if (!Float.isFinite(value)) {
        continue;
      }
      final var str = Float.toString(value);
      assertEquals(floatToIntBits(value), floatToIntBits(factory.create(str).readFloat()), () -> "seed=" + seed + " str=" + str);
    }
  }

  @Test
  void test_float_random_decimals_match_parse_float() {
    final long seed = new Random().nextLong();
    final var random = new Random(seed);
    final var sb = new StringBuilder(64);
    for (int i = 0; i < 20_000; ++i) {
      sb.setLength(0);
      if (random.nextBoolean()) {
        sb.append('-');
      }
      for (int d = 1 + random.nextInt(19); d > 0; --d) {
        sb.append((char) ('0' + random.nextInt(10)));
      }
      if (random.nextBoolean()) {
        sb.append('.');
        for (int d = 1 + random.nextInt(19); d > 0; --d) {
          sb.append((char) ('0' + random.nextInt(10)));
        }
      }
      if (random.nextBoolean()) {
        sb.append(random.nextBoolean() ? 'e' : 'E');
        if (random.nextBoolean()) {
          sb.append(random.nextBoolean() ? '+' : '-');
        }
        sb.append(random.nextInt(80));
      }
      final var str = sb.toString();
      assertEquals(floatToIntBits(Float.parseFloat(str)), floatToIntBits(factory.create(str).readFloat()), () -> "seed=" + seed + " str=" + str);
    }
  }

  @Test
  void test_random_round_trip() {
    final long seed = new Random().nextLong();
    final var random = new Random(seed);
    for (int i = 0; i < 20_000; ++i) {
      final double value = Double.longBitsToDouble(random.nextLong());
      if (!Double.isFinite(value)) {
        continue;
      }
      // Double.toString produces the shortest representation that round-trips.
      final var str = Double.toString(value);
      assertEquals(doubleToLongBits(value), doubleToLongBits(factory.create(str).readDouble()), () -> "seed=" + seed + " str=" + str);
    }
  }

  @Test
  void test_random_decimals_match_parse_double() {
    final long seed = new Random().nextLong();
    final var random = new Random(seed);
    final var sb = new StringBuilder(64);
    for (int i = 0; i < 20_000; ++i) {
      sb.setLength(0);
      if (random.nextBoolean()) {
        sb.append('-');
      }
      for (int d = 1 + random.nextInt(19); d > 0; --d) {
        sb.append((char) ('0' + random.nextInt(10)));
      }
      if (random.nextBoolean()) {
        sb.append('.');
        for (int d = 1 + random.nextInt(19); d > 0; --d) {
          sb.append((char) ('0' + random.nextInt(10)));
        }
      }
      if (random.nextBoolean()) {
        sb.append(random.nextBoolean() ? 'e' : 'E');
        if (random.nextBoolean()) {
          sb.append(random.nextBoolean() ? '+' : '-');
        }
        sb.append(random.nextInt(400));
      }
      final var str = sb.toString();
      assertEquals(doubleToLongBits(Double.parseDouble(str)), doubleToLongBits(factory.create(str).readDouble()), () -> "seed=" + seed + " str=" + str);
    }
  }
}
