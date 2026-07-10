package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.FieldSource;
import systems.comodal.jsoniter.factories.JsonIteratorFactory;

import java.math.RoundingMode;
import java.time.Instant;

import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
      ji = factory.create('"' + i + "0\"");
      assertThrows(JsonException.class, ji::readInt);

      ji = factory.create('"' + -i + "0\"");
      assertThrows(JsonException.class, ji::readInt);
    }

    ji = factory.create("\"9223372036854775808\"");
    assertThrows(JsonException.class, ji::readLong);

    for (long i = 1000000000000000000L; i < 9000000000000000000L; i += 100000000000000000L) {
      ji = factory.create('"' + i + "0\"");
      assertThrows(JsonException.class, ji::readLong);

      ji = factory.create('"' + -i + "0\"");
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
}
