package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.FieldSource;
import systems.comodal.jsoniter.factories.JsonIteratorFactory;

import static org.junit.jupiter.api.Assertions.*;

/// [JsonIterator#readUnscaledAsLong(int)] requires mark/reset support, so only
/// markable factories are exercised here.
@ParameterizedClass
@FieldSource("systems.comodal.jsoniter.TestFactories#MARKABLE_FACTORIES")
final class TestUnscaledLong {

  private final JsonIteratorFactory factory;

  TestUnscaledLong(final JsonIteratorFactory factory) {
    this.factory = factory;
  }

  @Test
  void testUnscaled() {
    assertEquals(12300L, factory.create("123").readUnscaledAsLong(2));
    assertEquals(12300L, factory.create("123.").readUnscaledAsLong(2));
    assertEquals(12300L, factory.create("123.0").readUnscaledAsLong(2));
    assertEquals(12300L, factory.create("123.00").readUnscaledAsLong(2));
    assertEquals(12345L, factory.create("123.45").readUnscaledAsLong(2));
    assertEquals(12345L, factory.create("123.456").readUnscaledAsLong(2));

    assertEquals(0L, factory.create("0.0").readUnscaledAsLong(2));
    assertEquals(0L, factory.create("0.00").readUnscaledAsLong(2));
    assertEquals(4L, factory.create("0.045").readUnscaledAsLong(2));
    assertEquals(45L, factory.create("0.45").readUnscaledAsLong(2));
    assertEquals(45L, factory.create("0.456").readUnscaledAsLong(2));

    assertEquals(12300L, factory.create("\"123\"").readUnscaledAsLong(2));
    assertEquals(12300L, factory.create("\"123.\"").readUnscaledAsLong(2));
    assertEquals(12300L, factory.create("\"123.0\"").readUnscaledAsLong(2));
    assertEquals(12300L, factory.create("\"123.00\"").readUnscaledAsLong(2));
    assertEquals(12345L, factory.create("\"123.45\"").readUnscaledAsLong(2));
    assertEquals(12345L, factory.create("\"123.456\"").readUnscaledAsLong(2));

    assertEquals(0L, factory.create("\"0.0\"").readUnscaledAsLong(2));
    assertEquals(0L, factory.create("\"0.00\"").readUnscaledAsLong(2));
    assertEquals(4L, factory.create("\"0.045\"").readUnscaledAsLong(2));
    assertEquals(45L, factory.create("\"0.45\"").readUnscaledAsLong(2));
    assertEquals(45L, factory.create("\"0.456\"").readUnscaledAsLong(2));

    var ji = factory.create("[\"123\",123]");
    assertEquals(123000L, ji.openArray().readUnscaledAsLong(3));
    assertEquals(123000L, ji.continueArray().readUnscaledAsLong(3));

    ji = factory.create("[\"123.\",123.]");
    assertEquals(123000L, ji.openArray().readUnscaledAsLong(3));
    assertEquals(123000L, ji.continueArray().readUnscaledAsLong(3));

    ji = factory.create("[\"123.0\",123.0]");
    assertEquals(123000L, ji.openArray().readUnscaledAsLong(3));
    assertEquals(123000L, ji.continueArray().readUnscaledAsLong(3));

    ji = factory.create("[\"123.00\",123.00]");
    assertEquals(123000L, ji.openArray().readUnscaledAsLong(3));
    assertEquals(123000L, ji.continueArray().readUnscaledAsLong(3));

    ji = factory.create("[\"123.45\",123.45]");
    assertEquals(123450L, ji.openArray().readUnscaledAsLong(3));
    assertEquals(123450L, ji.continueArray().readUnscaledAsLong(3));

    ji = factory.create("[\"123.4567\",123.4567]");
    assertEquals(123456L, ji.openArray().readUnscaledAsLong(3));
    assertEquals(123456L, ji.continueArray().readUnscaledAsLong(3));

    ji = factory.create("[\"0.0\",0.0]");
    assertEquals(0L, ji.openArray().readUnscaledAsLong(3));
    assertEquals(0L, ji.continueArray().readUnscaledAsLong(3));

    ji = factory.create("[\"0.00\",0.00]");
    assertEquals(0L, ji.openArray().readUnscaledAsLong(3));
    assertEquals(0L, ji.continueArray().readUnscaledAsLong(3));

    ji = factory.create("[\"0.045\",0.045]");
    assertEquals(45L, ji.openArray().readUnscaledAsLong(3));
    assertEquals(45L, ji.continueArray().readUnscaledAsLong(3));

    ji = factory.create("[\"0.45\",0.45]");
    assertEquals(450L, ji.openArray().readUnscaledAsLong(3));
    assertEquals(450L, ji.continueArray().readUnscaledAsLong(3));

    ji = factory.create("[\"0.456\",0.456]");
    assertEquals(456L, ji.openArray().readUnscaledAsLong(3));
    assertEquals(456L, ji.continueArray().readUnscaledAsLong(3));

    ji = factory.create("[\"45e2\",45E2]");
    assertEquals(4500_000L, ji.openArray().readUnscaledAsLong(3));
    assertEquals(4500_000L, ji.continueArray().readUnscaledAsLong(3));

    ji = factory.create("[\"45e-2\",45E-2]");
    assertEquals(450L, ji.openArray().readUnscaledAsLong(3));
    assertEquals(450L, ji.continueArray().readUnscaledAsLong(3));

    ji = factory.create("[\"0.45e2\",0.45E2]");
    assertEquals(45_000L, ji.openArray().readUnscaledAsLong(3));
    assertEquals(45_000L, ji.continueArray().readUnscaledAsLong(3));

    ji = factory.create("[\"1.45e2\",1.45E2]");
    assertEquals(145_000L, ji.openArray().readUnscaledAsLong(3));
    assertEquals(145_000L, ji.continueArray().readUnscaledAsLong(3));

    ji = factory.create("[\"1.456789e2\",1.456789E2]");
    assertEquals(145_678L, ji.openArray().readUnscaledAsLong(3));
    assertEquals(145_678L, ji.continueArray().readUnscaledAsLong(3));

    ji = factory.create("[\"0.45e-2\",0.45E-2]");
    assertEquals(4L, ji.openArray().readUnscaledAsLong(3));
    assertEquals(4L, ji.continueArray().readUnscaledAsLong(3));

    ji = factory.create("[\"1.45e-2\",1.45E-2]");
    assertEquals(14L, ji.openArray().readUnscaledAsLong(3));
    assertEquals(14L, ji.continueArray().readUnscaledAsLong(3));
  }

  @Test
  void testNegative() {
    assertEquals(-12345L, factory.create("-123.45").readUnscaledAsLong(2));
    assertEquals(-12345L, factory.create("\"-123.45\"").readUnscaledAsLong(2));
  }

  @Test
  void testBareAndTrailingZero() {
    // a bare zero ends the buffer at the integer, a zero inside an array does
    // not; both short-circuit without scaling
    assertEquals(0L, factory.create("0").readUnscaledAsLong(2));

    var ji = factory.create("[0,7]");
    ji.openArray();
    assertEquals(0L, ji.readUnscaledAsLong(2));
    assertEquals(7, ji.continueArray().readInt());
    ji.closeArray();

    ji = factory.create("[\"0\",7]");
    ji.openArray();
    assertEquals(0L, ji.readUnscaledAsLong(2));
    assertEquals(7, ji.continueArray().readInt());
    ji.closeArray();
  }

  @Test
  void testNegativeExponentWithoutFraction() {
    assertEquals(15L, factory.create("1500e-2").readUnscaledAsLong(0));
    assertEquals(15L, factory.create("\"1500e-2\"").readUnscaledAsLong(0));
  }

  @Test
  void testExponentBelowScale() {
    // exponent more negative than the scale: the fraction digits must be
    // divided away, not re-parsed under a negative scale limit
    assertEquals(0L, factory.create("1.5e-3").readUnscaledAsLong(1));
    assertEquals(0L, factory.create("\"1.5e-3\"").readUnscaledAsLong(1));
  }

  @Test
  void testLongBoundaries() {
    assertEquals(Long.MAX_VALUE, factory.create("9.223372036854775807").readUnscaledAsLong(18));
    assertEquals(9223372036854775800L, factory.create("922337203685477580").readUnscaledAsLong(1));

    // one past Long.MAX_VALUE in unscaled digits, through each overflow arm
    assertThrows(JsonException.class, () -> factory.create("9.223372036854775809").readUnscaledAsLong(18));
    assertThrows(JsonException.class, () -> factory.create("1.8446744073709551620").readUnscaledAsLong(19));
    assertThrows(JsonException.class, () -> factory.create("1844674407370955162").readUnscaledAsLong(1));
    assertThrows(JsonException.class, () -> factory.create("922337203685477580.8").readUnscaledAsLong(1));

    final var overflow = assertThrows(JsonException.class, () -> factory.create("9223372036854775808").readUnscaledAsLong(2));
    assertEquals("readUnscaledAsLong", overflow.op());
  }

  @Test
  void testRejectsNonNumericValue() {
    final var ex = assertThrows(JsonException.class, () -> factory.create("true").readUnscaledAsLong(2));
    assertEquals("readUnscaledAsLong", ex.op());
    assertTrue(ex.getMessage().contains("Must be a number"), ex.getMessage());
  }
}
