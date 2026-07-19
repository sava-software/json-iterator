package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.FieldSource;
import systems.comodal.jsoniter.factories.JsonIteratorFactory;

import static java.lang.Boolean.TRUE;
import static org.junit.jupiter.api.Assertions.*;

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
    ji.skipObjField();
    assertNull(ji.readString());

    ji = factory.create("{\"field\":null}");
    assertNull(ji.applyObject(TRUE, ((context, buf, offset, len, jsonIterator) -> {
          assertEquals("field", new String(buf, offset, len));
          assertEquals(TRUE, context);
          return jsonIterator.readString();
        })
    ));
  }

  @Test
  void test_null_as_Object() {
    var ji = factory.create("{\"field\":null}");
    ji.skipObjField();
    assertNull(ji.skipObjField());
  }

  @Test
  void test_null_as_BigDecimal() {
    var ji = factory.create("{\"field\":null}");
    ji.skipObjField();
    assertNull(ji.readBigDecimal());
  }

  @Test
  void test_null_as_BigInteger() {
    var ji = factory.create("{\"field\":null}");
    ji.skipObjField();
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
    assertNull(ji.skipUntil("a").readOrNull(jsonIterator -> jsonIterator.readMap(String::new, (_, inner) -> inner.readInt())));
    final var map = ji.skipUntil("b").readOrNull(jsonIterator -> jsonIterator.readMap(String::new, (_, inner) -> inner.readInt()));
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
  void test_read_primitive_or_default() {
    assertEquals(42L, factory.create("42").readLongOr(-1L));
    assertEquals(-1L, factory.create("null").readLongOr(-1L));
    assertEquals(-1L, factory.create("\"42\"").readLongOr(-1L));

    assertEquals(42, factory.create("42").readIntOr(-1));
    assertEquals(-1, factory.create("\"junk\"").readIntOr(-1));

    assertEquals((short) 42, factory.create("42").readShortOr((short) -1));
    assertEquals((short) -1, factory.create("null").readShortOr((short) -1));

    assertEquals(1.5, factory.create("1.5").readDoubleOr(Double.NaN));
    assertEquals(Double.NaN, factory.create("null").readDoubleOr(Double.NaN));

    assertEquals(1.5f, factory.create("1.5").readFloatOr(Float.NaN));
    assertEquals(Float.NaN, factory.create("null").readFloatOr(Float.NaN));

    assertTrue(factory.create("true").readBooleanOr(false));
    assertFalse(factory.create("false").readBooleanOr(true));
    assertTrue(factory.create("null").readBooleanOr(true));
    assertFalse(factory.create("\"true\"").readBooleanOr(false));
  }

  @Test
  void test_read_primitive_or_default_skips_and_positions() {
    final var ji = factory.create("{\"a\":\"nope\",\"b\":7,\"c\":{\"x\":1},\"d\":8}");
    assertEquals(-1L, ji.skipUntil("a").readLongOr(-1L));
    assertEquals(7L, ji.skipUntil("b").readLongOr(-1L));
    assertEquals(-1, ji.skipUntil("c").readIntOr(-1));
    assertEquals(8, ji.skipUntil("d").readIntOr(-1));
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
