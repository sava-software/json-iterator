package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.FieldSource;
import systems.comodal.jsoniter.factories.JsonIteratorFactory;

import static java.lang.Boolean.TRUE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@ParameterizedClass
@FieldSource("systems.comodal.jsoniter.TestFactories#FACTORIES")
final class TestNull {

  private final JsonIteratorFactory factory;

  TestNull(final JsonIteratorFactory factory) {
    this.factory = factory;
  }

  @Test
  void test_null_as_String() {
    var ji = factory.create("{\"field\":null}");
    ji.readObject();
    assertNull(ji.readString());

    ji = factory.create("{\"field\":null}");
    assertNull(ji.applyObject(TRUE, ((context, buf, offset, len, jsonIterator) -> {
      assertEquals("field", new String(buf, offset, len));
      assertEquals(TRUE, context);
      return jsonIterator.readString();
    })));
  }

  @Test
  void test_null_as_Object() {
    var ji = factory.create("{\"field\":null}");
    ji.readObject();
    assertNull(ji.readObject());
  }

  @Test
  void test_null_as_BigDecimal() {
    var ji = factory.create("{\"field\":null}");
    ji.readObject();
    assertNull(ji.readBigDecimal());
  }

  @Test
  void test_null_as_BigInteger() {
    var ji = factory.create("{\"field\":null}");
    ji.readObject();
    assertNull(ji.readBigInteger());
  }

  @Test
  void test_read_or_null() {
    var ji = factory.create("null");
    assertNull(ji.readOrNull(JsonIterator::readString));

    ji = factory.create("\"value\"");
    assertEquals("value", ji.readOrNull(JsonIterator::readString));

    ji = factory.create("42");
    assertEquals(42, ji.readOrNull(JsonIterator::readInt));
  }

  @Test
  void test_read_or_null_consumes_null_field_value() {
    final var ji = factory.create("{\"a\":null,\"b\":7}");
    assertNull(ji.skipUntil("a").readOrNull(JsonIterator::readString));
    assertEquals(7, (int) ji.skipUntil("b").readOrNull(JsonIterator::readInt));
  }

  @Test
  void test_read_or_null_object_value() {
    final var ji = factory.create("{\"a\":null,\"b\":{\"x\":1}}");
    assertNull(ji.skipUntil("a").readOrNull(jsonIterator -> jsonIterator.readMap(String::new, (key, inner) -> inner.readInt())));
    final var map = ji.skipUntil("b").readOrNull(jsonIterator -> jsonIterator.readMap(String::new, (key, inner) -> inner.readInt()));
    assertEquals(1, map.get("x"));
  }

  @Test
  void test_read_or_null_typed() {
    assertEquals("s", factory.create("\"s\"").readOrNull(ValueType.STRING, JsonIterator::readString));
    assertNull(factory.create("null").readOrNull(ValueType.STRING, JsonIterator::readString));
    assertNull(factory.create("42").readOrNull(ValueType.STRING, JsonIterator::readString));
    assertNull(factory.create("{\"x\":1}").readOrNull(ValueType.STRING, JsonIterator::readString));

    assertEquals(42, factory.create("42").readOrNull(ValueType.NUMBER, JsonIterator::readInt));
    assertNull(factory.create("\"42\"").readOrNull(ValueType.NUMBER, JsonIterator::readInt));
  }

  @Test
  void test_read_or_null_typed_skips_and_positions() {
    final var ji = factory.create("{\"a\":\"nope\",\"b\":7}");
    assertNull(ji.skipUntil("a").readOrNull(ValueType.NUMBER, JsonIterator::readInt));
    assertEquals(7, (int) ji.skipUntil("b").readOrNull(JsonIterator::readInt));
  }

  @Test
  void test_read_or_null_typed_list_elements() {
    // Mixed-type array: non-objects are skipped and padded as null entries.
    final var ji = factory.create("[{\"x\":1},null,\"junk\",{\"x\":2}]");
    final var list = ji.readList(jsonIterator -> jsonIterator.readOrNull(
        ValueType.OBJECT,
        inner -> inner.readMap(String::new, (_, valueJi) -> valueJi.readInt())
    ));
    assertEquals(4, list.size());
    assertEquals(1, list.get(0).get("x"));
    assertNull(list.get(1));
    assertNull(list.get(2));
    assertEquals(2, list.get(3).get("x"));
  }
}
