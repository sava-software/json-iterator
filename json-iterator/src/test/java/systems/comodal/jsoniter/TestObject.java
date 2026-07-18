package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.FieldSource;
import systems.comodal.jsoniter.factories.JsonIteratorFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

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
          return jsonIterator.applyObject(FALSE, (_context, _buf, _, _len, _) -> {
                assertEquals(FALSE, _context);
                assertEquals(-1, _len);
                assertNull(_buf);
                return null;
              }
          );
        })
    ));

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
  void test_skip_until_from_stream() {
    final var bytes = TRICKY_FIELDS_JSON.getBytes(StandardCharsets.UTF_8);
    final var ji = JsonIterator.parse(new ByteArrayInputStream(bytes));
    assertEquals(42, ji.skipUntil("want").readInt());
  }

  @Test
  void test_read_null() {
    var ji = factory.create("null");
    assertTrue(ji.readNull());
  }

  @Test
  void test_read_map() {
    final var ji = factory.create("""
        {"a":1,"b":2}""");
    assertEquals(
        Map.of("a", 1, "b", 2),
        ji.readMap(String::new, (_, jsonIterator) -> jsonIterator.readInt())
    );
  }

  @Test
  void test_read_map_key_passed_to_value_parser() {
    final var ji = factory.create("""
        {"a":1,"b":2}""");
    assertEquals(
        Map.of("a", "a=1", "b", "b=2"),
        ji.readMap(String::new, (key, jsonIterator) -> key + "=" + jsonIterator.readInt())
    );
  }

  @Test
  void test_read_map_span_parsed_keys() {
    final var ji = factory.create("""
        {"1":"one","2":"two"}""");
    assertEquals(
        Map.of(1, "one", 2, "two"),
        ji.readMap(
            (buf, offset, len) -> Integer.parseInt(new String(buf, offset, len)),
            (_, jsonIterator) -> jsonIterator.readString()
        )
    );
  }

  @Test
  void test_read_map_empty() {
    final var ji = factory.create("{}");
    assertTrue(ji.readMap(String::new, (_, jsonIterator) -> jsonIterator.readInt()).isEmpty());
  }

  @Test
  void test_read_map_null() {
    var ji = factory.create("null");
    assertTrue(ji.readMap(String::new, (_, jsonIterator) -> jsonIterator.readInt()).isEmpty());

    ji = factory.create("null");
    assertNull(ji.notNull() ? ji.readMap(String::new, (_, jsonIterator) -> jsonIterator.readInt()) : null);
  }

  @Test
  void test_read_map_duplicate_key_last_wins() {
    final var ji = factory.create("""
        {"a":1,"a":2}""");
    assertEquals(
        Map.of("a", 2),
        ji.readMap(String::new, (_, jsonIterator) -> jsonIterator.readInt())
    );
  }

  @Test
  void test_read_map_supplied_map() {
    final var ji = factory.create("""
        {"A":1,"a":2}""");
    final var map = ji.readMap(
        new TreeMap<>(String.CASE_INSENSITIVE_ORDER),
        String::new,
        (_, jsonIterator) -> jsonIterator.readInt()
    );
    assertEquals(1, map.size());
    assertEquals(2, map.get("a"));
    assertEquals(2, map.get("A"));
  }

  @Test
  void test_read_map_nested_values() {
    final var ji = factory.create("""
        {"outer":{"inner":1}}""");
    assertEquals(
        Map.of("outer", Map.of("inner", 1)),
        ji.readMap(
            String::new,
            (_, jsonIterator) -> jsonIterator.readMap(String::new, (_, innerJi) -> innerJi.readInt())
        )
    );
  }

  @Test
  void test_read_map_as_field_value() {
    final var ji = factory.create("""
        {"m":{"x":1},"after":2}""");
    assertEquals(
        Map.of("x", 1),
        ji.skipUntil("m").readMap(String::new, (_, jsonIterator) -> jsonIterator.readInt())
    );
    assertEquals(2, ji.skipUntil("after").readInt());
  }

  private record Pair(int x, int y) {

    private static final class Parser implements FieldBufferPredicate, FieldIndexPredicate, Supplier<Pair> {

      private int x;
      private int y;

      private Pair create() {
        return new Pair(x, y);
      }

      @Override
      public Pair get() {
        return create();
      }

      @Override
      public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
        if (JsonIterator.fieldEquals("x", buf, offset, len)) {
          x = ji.readInt();
        } else if (JsonIterator.fieldEquals("y", buf, offset, len)) {
          y = ji.readInt();
        } else {
          ji.skip();
        }
        return true;
      }

      private static final FieldMatcher FIELDS = FieldMatcher.of("x", "y");

      @Override
      public boolean test(final int fieldIndex, final JsonIterator ji) {
        switch (fieldIndex) {
          case 0 -> x = ji.readInt();
          case 1 -> y = ji.readInt();
          default -> ji.skip();
        }
        return true;
      }
    }
  }

  @Test
  void test_parse_object() {
    var ji = factory.create("""
        {"x":1,"unknown":true,"y":2}""");
    assertEquals(new Pair(1, 2), ji.parseObject(new Pair.Parser()));

    ji = factory.create("""
        {"x":1,"unknown":true,"y":2}""");
    assertEquals(new Pair(1, 2), ji.parseObject(new Pair.Parser(), Pair.Parser::create));
  }

  @Test
  void test_parse_object_matcher() {
    var ji = factory.create("""
        {"x":1,"unknown":true,"y":2}""");
    assertEquals(new Pair(1, 2), ji.parseObject(Pair.Parser.FIELDS, new Pair.Parser()));

    ji = factory.create("""
        {"x":1,"unknown":true,"y":2}""");
    assertEquals(new Pair(1, 2), ji.parseObject(Pair.Parser.FIELDS, new Pair.Parser(), Pair.Parser::create));
  }

  @Test
  void test_parse_object_null_and_empty() {
    // testObject consumes a JSON null or empty object without invoking the
    // predicate; the finisher still runs over the untouched parser state.
    assertEquals(new Pair(0, 0), factory.create("null").parseObject(new Pair.Parser()));
    assertEquals(new Pair(0, 0), factory.create("{}").parseObject(new Pair.Parser()));
    assertEquals(new Pair(0, 0), factory.create("null").parseObject(Pair.Parser.FIELDS, new Pair.Parser()));
    assertEquals(new Pair(0, 0), factory.create("null").parseObject(new Pair.Parser(), Pair.Parser::create));
  }

  @Test
  void test_parse_object_as_field_value() {
    final var ji = factory.create("""
        {"pair":{"y":4,"x":3},"after":5}""");
    assertEquals(
        new Pair(3, 4),
        ji.skipUntil("pair").parseObject(Pair.Parser.FIELDS, new Pair.Parser())
    );
    assertEquals(5, ji.skipUntil("after").readInt());
  }
}
