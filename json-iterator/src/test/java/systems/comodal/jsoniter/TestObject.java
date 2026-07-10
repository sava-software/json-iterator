package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.FieldSource;
import systems.comodal.jsoniter.factories.JsonIteratorFactory;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static org.junit.jupiter.api.Assertions.*;

@ParameterizedClass
@FieldSource("systems.comodal.jsoniter.TestFactories#FACTORIES")
final class TestObject {

  private final JsonIteratorFactory factory;

  TestObject(final JsonIteratorFactory factory) {
    this.factory = factory;
  }

  @Test
  void test_empty_object() {
    var ji = factory.create("{}");
    assertNull(ji.readObject());
  }

  @Test
  void test_one_field() {
    final var json = """
        { "field1"
        :
        \t"hello" }""";

    var ji = factory.create(json);
    assertEquals("field1", ji.readObject());
    assertEquals("hello", ji.readString());
    assertNull(ji.readObject());

    ji = factory.create(json);
    assertNull(ji.applyObject(TRUE, ((context, buf, offset, len, jsonIterator) -> {
      assertEquals(TRUE, context);
      assertEquals("field1", new String(buf, offset, len));
      assertEquals("hello", jsonIterator.readString());
      return jsonIterator.applyObject(FALSE, (_context, _buf, o, _len, _ji) -> {
        assertEquals(FALSE, _context);
        assertEquals(-1, _len);
        assertNull(_buf);
        return null;
      });
    })));

    ji = factory.create(json);
    assertEquals(ji, ji.skipObjField());
    assertEquals("hello", ji.readString());
    assertNull(ji.skipObjField());
  }

  @Test
  void test_two_fields() {
    var ji = factory.create("{ \"field1\" : \"hello\" , \"field2\": \"world\" }");
    assertEquals("field1", ji.readObject());
    assertEquals("hello", ji.readString());
    assertEquals("field2", ji.readObject());
    assertEquals("world", ji.readString());
    assertNull(ji.readObject());

    ji = factory.create("{ \"field1\" : \"hello\" , \"field2\": \"world\" }");
    assertEquals("world", ji.skipUntil("field2").readString());
  }

  @Test
  void test_skip_until() {
    var ji = factory.create("{ \"field1\" : \"hello\" , \"field2\": {\"nested1\" : \"blah\", \"nested2\": \"world\"} }");
    assertEquals("world", ji.skipUntil("field2").skipUntil("nested2").readString());
  }

  @Test
  void test_read_null() {
    var ji = factory.create("null");
    assertTrue(ji.readNull());
  }
}
