package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.FieldSource;
import systems.comodal.jsoniter.factories.JsonIteratorFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

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

  private static final String TRICKY_FIELDS_JSON = "{\"wan\":1,\"wanted\":2,\"a\\\"b\":3,\"中文\":4,\"want\":42}";

  @Test
  void test_skip_until_tricky_field_names() {
    // shorter and longer names sharing a prefix with the target, escaped
    // names, and multi-byte names — the mismatch, name-longer, and slow
    // paths of the in-place field comparison
    assertEquals(42, factory.create(TRICKY_FIELDS_JSON).skipUntil("want").readInt());
    assertEquals(1, factory.create(TRICKY_FIELDS_JSON).skipUntil("wan").readInt());
    assertEquals(2, factory.create(TRICKY_FIELDS_JSON).skipUntil("wanted").readInt());
    assertEquals(3, factory.create(TRICKY_FIELDS_JSON).skipUntil("a\"b").readInt());
    assertEquals(4, factory.create(TRICKY_FIELDS_JSON).skipUntil("中文").readInt());
    assertNull(factory.create(TRICKY_FIELDS_JSON).skipUntil("wa"));
    assertNull(factory.create(TRICKY_FIELDS_JSON).skipUntil("wants"));

    // code-escaped names decode identically on every source type
    final var tabJson = "{\"a\\tb\":1,\"c\":2}";
    assertEquals(1, factory.create(tabJson).skipUntil("a\tb").readInt());
    assertEquals(2, factory.create(tabJson).skipUntil("c").readInt());
  }

  @Test
  void test_skip_until_across_buffer_boundaries() {
    // InputStream sources are read fully upfront, so the bufSize sweep just
    // re-verifies field matching through the deprecated entry points
    final var bytes = TRICKY_FIELDS_JSON.getBytes(StandardCharsets.UTF_8);
    for (int bufSize = 4; bufSize <= bytes.length; ++bufSize) {
      final var ji = JsonIterator.parse(new ByteArrayInputStream(bytes), bufSize);
      assertEquals(42, ji.skipUntil("want").readInt(), "bufSize=" + bufSize);
    }
  }

  @Test
  void test_read_null() {
    var ji = factory.create("null");
    assertTrue(ji.readNull());
  }
}
