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
}
