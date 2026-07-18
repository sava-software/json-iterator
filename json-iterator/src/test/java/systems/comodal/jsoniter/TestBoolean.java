package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.FieldSource;
import systems.comodal.jsoniter.factories.JsonIteratorFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@ParameterizedClass
@FieldSource("systems.comodal.jsoniter.TestFactories#FACTORIES")
final class TestBoolean {

  private final JsonIteratorFactory factory;

  TestBoolean(final JsonIteratorFactory factory) {
    this.factory = factory;
  }

  @Test
  void test_boolean_array() {
    final var json = "[true,false,null,true]";
    var ji = factory.create(json);
    ji.readArray();
    assertTrue(ji.readBoolean());
    ji.readArray();
    assertFalse(ji.readBoolean());
    ji.readArray();
    assertTrue(ji.readNull());
    ji.readArray();
    assertTrue(ji.readBoolean());
    assertFalse(ji.readArray());

    ji = factory.create(json);
    assertTrue(ji.openArray().readBoolean());
    assertFalse(ji.continueArray().readBoolean());
    assertTrue(ji.continueArray().readNull());
    assertTrue(ji.continueArray().readBoolean());
    assertNotNull(ji.closeArray());
  }

  @Test
  void test_booleans() {
    assertTrue(factory.create("true").readBoolean());
    assertFalse(factory.create("false").readBoolean());
    assertTrue(factory.create("null").readNull());
    assertFalse(factory.create("true").readNull());
    assertFalse(factory.create("false").readNull());
  }

  @Test
  void test_not_null() {
    assertFalse(factory.create("null").notNull());
    assertTrue(factory.create("true").notNull());
    assertTrue(factory.create("\"a\"").notNull());

    // consumes the null, stays in place otherwise
    final var ji = factory.create("[null,1]");
    assertFalse(ji.openArray().notNull());
    assertTrue(ji.continueArray().notNull());
    assertEquals(1, ji.readInt());
    assertNotNull(ji.closeArray());
  }

  @Test
  void test_invalid_literals() {
    assertThrows(JsonException.class, () -> factory.create("trux").readBoolean());
    assertThrows(JsonException.class, () -> factory.create("tru").readBoolean());
    assertThrows(JsonException.class, () -> factory.create("falsy").readBoolean());
    assertThrows(JsonException.class, () -> factory.create("fals").readBoolean());
    assertThrows(JsonException.class, () -> factory.create("nul").readNull());
    assertThrows(JsonException.class, () -> factory.create("nulL").readString());
    assertThrows(JsonException.class, () -> factory.create("[tru]").openArray().skip());
    assertThrows(JsonException.class, () -> factory.create("[nell]").openArray().skip());
    assertThrows(JsonException.class, () -> factory.create("[folse]").openArray().skip());
  }

  @Test
  void test_literals_from_stream() {
    final var bytes = "{\"a\":true,\"b\":false,\"c\":null,\"want\":1}".getBytes(StandardCharsets.US_ASCII);
    var ji = JsonIterator.parse(new ByteArrayInputStream(bytes));
    assertEquals(1, ji.skipUntil("want").readInt());

    ji = JsonIterator.parse(new ByteArrayInputStream(bytes));
    assertTrue(ji.skipUntil("a").readBoolean());
    assertFalse(ji.skipUntil("b").readBoolean());
    assertTrue(ji.skipUntil("c").readNull());
  }
}
