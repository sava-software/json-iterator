package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.FieldSource;
import systems.comodal.jsoniter.factories.JsonIteratorFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
