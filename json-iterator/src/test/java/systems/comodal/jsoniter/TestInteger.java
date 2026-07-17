package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.FieldSource;
import systems.comodal.jsoniter.factories.JsonIteratorFactory;

import java.math.RoundingMode;
import java.time.Instant;

import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ParameterizedClass
@FieldSource("systems.comodal.jsoniter.TestFactories#FACTORIES")
final class TestInteger {

  private final JsonIteratorFactory factory;

  TestInteger(final JsonIteratorFactory factory) {
    this.factory = factory;
  }

  @Test
  void test_positive_negative_int() {
    assertEquals(0, factory.create("0").readInt());
    assertEquals(4321, factory.create("4321").readInt());
    assertEquals(54321, factory.create("54321").readInt());
    assertEquals(654321, factory.create("654321").readInt());
    assertEquals(7654321, factory.create("7654321").readInt());
    assertEquals(87654321, factory.create("87654321").readInt());
    assertEquals(987654321, factory.create("987654321").readInt());
    assertEquals(2147483647, factory.create("2147483647").readInt());
    assertEquals(-4321, factory.create("-4321").readInt());
    assertEquals(-2147483648, factory.create("-2147483648").readInt());


    assertEquals(0, factory.create("\"0\"").readInt());
    assertEquals(4321, factory.create("\"4321\"").readInt());
    assertEquals(54321, factory.create("\"54321\"").readInt());
    assertEquals(654321, factory.create("\"654321\"").readInt());
    assertEquals(7654321, factory.create("\"7654321\"").readInt());
    assertEquals(87654321, factory.create("\"87654321\"").readInt());
    assertEquals(987654321, factory.create("\"987654321\"").readInt());
    assertEquals(2147483647, factory.create("\"2147483647\"").readInt());
    assertEquals(-4321, factory.create("\"-4321\"").readInt());
    assertEquals(-2147483648, factory.create("\"-2147483648\"").readInt());
  }

  @Test
  void test_positive_negative_long() {
    assertEquals(0L, factory.create("0").readLong());
    assertEquals(4321L, factory.create("4321").readLong());
    assertEquals(54321L, factory.create("54321").readLong());
    assertEquals(654321L, factory.create("654321").readLong());
    assertEquals(7654321L, factory.create("7654321").readLong());
    assertEquals(87654321L, factory.create("87654321").readLong());
    assertEquals(987654321L, factory.create("987654321").readLong());
    assertEquals(9223372036854775807L, factory.create("9223372036854775807").readLong());
    assertEquals(-4321L, factory.create("-4321").readLong());
    assertEquals(-9223372036854775808L, factory.create("-9223372036854775808").readLong());

    assertEquals(0L, factory.create("\"0\"").readLong());
    assertEquals(4321L, factory.create("\"4321\"").readLong());
    assertEquals(54321L, factory.create("\"54321\"").readLong());
    assertEquals(654321L, factory.create("\"654321\"").readLong());
    assertEquals(7654321L, factory.create("\"7654321\"").readLong());
    assertEquals(87654321L, factory.create("\"87654321\"").readLong());
    assertEquals(987654321L, factory.create("\"987654321\"").readLong());
    assertEquals(9223372036854775807L, factory.create("\"9223372036854775807\"").readLong());
    assertEquals(-4321L, factory.create("\"-4321\"").readLong());
    assertEquals(-9223372036854775808L, factory.create("\"-9223372036854775808\"").readLong());
  }

  @Test
  void test_positive_negative_short() {
    assertEquals((short) 0, factory.create("0").readShort());
    assertEquals((short) 1, factory.create("1").readShort());
    assertEquals((short) 321, factory.create("321").readShort());
    assertEquals((short) 4321, factory.create("4321").readShort());
    assertEquals((short) -4321, factory.create("-4321").readShort());

    assertEquals((short) 0, factory.create("\"0\"").readShort());
    assertEquals((short) 1, factory.create("\"1\"").readShort());
    assertEquals((short) 321, factory.create("\"321\"").readShort());
    assertEquals((short) 4321, factory.create("\"4321\"").readShort());
    assertEquals((short) -4321, factory.create("\"-4321\"").readShort());
  }

  @Test
  void test_max_min_short() {
    assertEquals(Short.MAX_VALUE, factory.create(Short.toString(Short.MAX_VALUE)).readShort());
    assertEquals((short) (Short.MAX_VALUE - 1), factory.create(Short.toString((short) (Short.MAX_VALUE - 1))).readShort());
    assertEquals((short) (Short.MIN_VALUE + 1), factory.create(Short.toString((short) (Short.MIN_VALUE + 1))).readShort());
    assertEquals(Short.MIN_VALUE, factory.create(Short.toString(Short.MIN_VALUE)).readShort());

    assertEquals(Short.MAX_VALUE, factory.create(String.format("\"%d\"", Short.MAX_VALUE)).readShort());
    assertEquals((short) (Short.MAX_VALUE - 1), factory.create(String.format("\"%d\"", Short.MAX_VALUE - 1)).readShort());
    assertEquals((short) (Short.MIN_VALUE + 1), factory.create(String.format("\"%d\"", Short.MIN_VALUE + 1)).readShort());
    assertEquals(Short.MIN_VALUE, factory.create(String.format("\"%d\"", Short.MIN_VALUE)).readShort());
  }

  @Test
  void test_short_overflow() {
    var ji = factory.create("32768");
    assertThrows(JsonException.class, ji::readShort);

    ji = factory.create("-32769");
    assertThrows(JsonException.class, ji::readShort);

    ji = factory.create("2147483647");
    assertThrows(JsonException.class, ji::readShort);

    ji = factory.create("-2147483648");
    assertThrows(JsonException.class, ji::readShort);

    ji = factory.create("\"32768\"");
    assertThrows(JsonException.class, ji::readShort);

    ji = factory.create("\"-32769\"");
    assertThrows(JsonException.class, ji::readShort);
  }

  @Test
  void test_max_min_short_array() {
    final var ji = factory.create("[32767,-32768]");
    ji.readArray();
    assertEquals(Short.MAX_VALUE, ji.readShort());
    ji.readArray();
    assertEquals(Short.MIN_VALUE, ji.readShort());
    assertFalse(ji.readArray());
  }

  @Test
  void test_max_min_int() {
    assertEquals(Integer.MAX_VALUE, factory.create(Integer.toString(Integer.MAX_VALUE)).readInt());
    assertEquals(Integer.MAX_VALUE - 1, factory.create(Integer.toString(Integer.MAX_VALUE - 1)).readInt());
    assertEquals(Integer.MIN_VALUE + 1, factory.create(Integer.toString(Integer.MIN_VALUE + 1)).readInt());
    assertEquals(Integer.MIN_VALUE, factory.create(Integer.toString(Integer.MIN_VALUE)).readInt());


    assertEquals(Integer.MAX_VALUE, factory.create(String.format("\"%d\"", Integer.MAX_VALUE)).readInt());
    assertEquals(Integer.MAX_VALUE - 1, factory.create(String.format("\"%d\"", Integer.MAX_VALUE - 1)).readInt());
    assertEquals(Integer.MIN_VALUE + 1, factory.create(String.format("\"%d\"", Integer.MIN_VALUE + 1)).readInt());
    assertEquals(Integer.MIN_VALUE, factory.create(String.format("\"%d\"", Integer.MIN_VALUE)).readInt());
  }

  @Test
  void test_max_min_long() {
    assertEquals(Long.MAX_VALUE, factory.create(Long.toString(Long.MAX_VALUE)).readLong());
    assertEquals(Long.MAX_VALUE - 1, factory.create(Long.toString(Long.MAX_VALUE - 1)).readLong());
    assertEquals(Long.MIN_VALUE + 1, factory.create(Long.toString(Long.MIN_VALUE + 1)).readLong());
    assertEquals(Long.MIN_VALUE, factory.create(Long.toString(Long.MIN_VALUE)).readLong());


    assertEquals(Long.MAX_VALUE, factory.create(String.format("\"%d\"", Long.MAX_VALUE)).readLong());
    assertEquals(Long.MAX_VALUE - 1, factory.create(String.format("\"%d\"", Long.MAX_VALUE - 1)).readLong());
    assertEquals(Long.MIN_VALUE + 1, factory.create(String.format("\"%d\"", Long.MIN_VALUE + 1)).readLong());
    assertEquals(Long.MIN_VALUE, factory.create(String.format("\"%d\"", Long.MIN_VALUE)).readLong());
  }

  @Test
  void test_large_number() {
    var ji = factory.create("2147483648");
    assertThrows(JsonException.class, ji::readInt);

    for (int i = 300000000; i < 2000000000; i += 10000000) {
      ji = factory.create(i + "0");
      assertThrows(JsonException.class, ji::readInt);

      ji = factory.create(-i + "0");
      assertThrows(JsonException.class, ji::readInt);
    }

    ji = factory.create("9223372036854775808");
    assertThrows(JsonException.class, ji::readLong);

    for (long i = 1000000000000000000L; i < 9000000000000000000L; i += 100000000000000000L) {
      ji = factory.create(i + "0");
      assertThrows(JsonException.class, ji::readLong);

      ji = factory.create(-i + "0");
      assertThrows(JsonException.class, ji::readLong);
    }

    ji = factory.create("\"2147483648\"");
    assertThrows(JsonException.class, ji::readInt);

    for (int i = 300000000; i < 2000000000; i += 10000000) {
      ji = factory.create("\"" + i + "0\"");
      assertThrows(JsonException.class, ji::readInt);

      ji = factory.create("\"" + -i + "0\"");
      assertThrows(JsonException.class, ji::readInt);
    }

    ji = factory.create("\"9223372036854775808\"");
    assertThrows(JsonException.class, ji::readLong);

    for (long i = 1000000000000000000L; i < 9000000000000000000L; i += 100000000000000000L) {
      ji = factory.create("\"" + i + "0\"");
      assertThrows(JsonException.class, ji::readLong);

      ji = factory.create("\"" + -i + "0\"");
      assertThrows(JsonException.class, ji::readLong);
    }
  }

  @Test
  void test_leading_zero() {
    var ji = factory.create("0");
    assertEquals(0, ji.readInt());

    ji = factory.create("0");
    assertEquals(0L, ji.readLong());

    ji = factory.create("01");
    assertThrows(JsonException.class, ji::readInt);

    ji = factory.create("02147483647");
    assertThrows(JsonException.class, ji::readInt);

    ji = factory.create("01");
    assertThrows(JsonException.class, ji::readLong);

    ji = factory.create("09223372036854775807");
    assertThrows(JsonException.class, ji::readLong);


    ji = factory.create("\"0\"");
    assertEquals(0, ji.readInt());

    ji = factory.create("\"0\"");
    assertEquals(0L, ji.readLong());

    ji = factory.create("\"01\"");
    assertThrows(JsonException.class, ji::readInt);

    ji = factory.create("\"02147483647\"");
    assertThrows(JsonException.class, ji::readInt);

    ji = factory.create("\"01\"");
    assertThrows(JsonException.class, ji::readLong);

    ji = factory.create("\"09223372036854775807\"");
    assertThrows(JsonException.class, ji::readLong);
  }

  @Test
  void test_max_int() {
    var ji = factory.create("[2147483647,-2147483648]");
    ji.readArray();
    assertEquals(Integer.MAX_VALUE, ji.readInt());
    ji.readArray();
    assertEquals(Integer.MIN_VALUE, ji.readInt());


    ji = factory.create("[\"2147483647\",\"-2147483648\"]");
    ji.readArray();
    assertEquals(Integer.MAX_VALUE, ji.readInt());
    ji.readArray();
    assertEquals(Integer.MIN_VALUE, ji.readInt());
  }

  @Test
  void testParseMicroseconds() {
    final CharBufferFunction<Instant> MICROS_PARSER = (buf, offset, len) -> {
      final int secondsLength = len - 6;
      final long seconds = Long.parseLong(new String(buf, offset, secondsLength));
      final long micros = Long.parseLong(new String(buf, offset + secondsLength, 6));
      return Instant.ofEpochSecond(seconds, MICROSECONDS.toNanos(micros));
    };

    final var json = "{\"timestamp\": 1694687692989999}";
    var ji = factory.create(json);
    ji.skipObjField();
    var instant = ji.applyNumberChars(MICROS_PARSER);
    final var expected = Instant.ofEpochSecond(1694687692, 989999000);
    assertEquals(expected, instant);

    ji = factory.create(json);
    ji.skipObjField();
    final var micros = ji.readBigDecimal().movePointLeft(6);
    final var seconds = micros.setScale(0, RoundingMode.DOWN);
    final var nanos = micros.subtract(seconds).movePointRight(9);
    instant = Instant.ofEpochSecond(seconds.longValue(), nanos.longValue());
    assertEquals(expected, instant);
  }

  @Test
  void test_non_ascii_terminates_or_rejects() {
    // fuzz-hardening regression: digit resolution indexed a 127-entry table
    // with the raw source value — a multibyte char or negative byte faulted
    // instead of terminating the scan or rejecting the token
    assertEquals(12, factory.create("12é").readInt());
    assertEquals(12L, factory.create("12é").readLong());
    assertEquals(12, factory.create("12中3").readInt());
    assertEquals(12345678901L, factory.create("12345678901é").readLong());
    assertThrows(JsonException.class, () -> factory.create("é").readInt());
    assertThrows(JsonException.class, () -> factory.create("中").readLong());
    // a lone minus at end-of-input rejects instead of reading past the buffer
    assertThrows(JsonException.class, () -> factory.create("-").readInt());
    assertThrows(JsonException.class, () -> factory.create("-").readLong());
  }

  @Test
  void test_number_chars_leading_whitespace() {
    // the applyNumberChars family enters the number scan without a peekToken;
    // every whitespace char skips ahead of the token, not just a space
    for (final var ws : new String[]{"", " ", "\t", "\n", "\r", " \t\r\n "}) {
      final var ji = factory.create("{\"n\":" + ws + "123}");
      ji.skipObjField();
      assertEquals("123", ji.readNumberAsString(), "'" + ws + "'");
    }
  }
}
