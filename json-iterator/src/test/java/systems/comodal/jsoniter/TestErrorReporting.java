package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.FieldSource;
import systems.comodal.jsoniter.factories.JsonIteratorFactory;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/// Exercises [BaseJsonIterator#reportError]: every case asserts on the
/// message and the structured [JsonException] accessors so the reported
/// window can be judged — and iterated on — against the actual failure.
@ParameterizedClass
@FieldSource("systems.comodal.jsoniter.TestFactories#FACTORIES")
final class TestErrorReporting {

  private final JsonIteratorFactory factory;

  TestErrorReporting(final JsonIteratorFactory factory) {
    this.factory = factory;
  }

  private JsonException expectError(final String json, final Consumer<JsonIterator> parse) {
    final var ji = factory.create(json);
    final var ex = assertThrows(JsonException.class, () -> parse.accept(ji));
    // every reportError failure carries the structured context
    assertNotNull(ex.op(), ex.getMessage());
    assertNotNull(ex.context(), ex.getMessage());
    assertTrue(ex.offset() >= 0, ex.getMessage());
    assertTrue(ex.context().indexOf('»') >= 0, ex.getMessage());
    assertTrue(ex.getMessage().contains(ex.context()), ex.getMessage());
    return ex;
  }

  // --- intentionally bad JSON ---

  @Test
  void test_truncated_document() {
    final var ex = expectError("{\"value\":", ji -> ji.skipUntil("value").readInt());
    assertTrue(ex.getMessage().contains("unexpected end"), ex.getMessage());
    // the marker lands at the end of input: everything read, nothing after
    assertEquals("{\"value\":»", ex.context());
    assertEquals(9, ex.offset());
  }

  @Test
  void test_invalid_literal() {
    final var ex = expectError("[true, falze]", ji -> {
      ji.readArray();
      ji.readBoolean();
      ji.readArray();
      ji.readBoolean();
    });
    assertEquals("skipFalse", ex.op());
    assertTrue(ex.getMessage().contains("expected false"), ex.getMessage());
    // the bad literal is visible in the window, split by the marker where
    // the parse stopped: the 'f' token was consumed, the rest was peeked
    assertTrue(ex.context().contains("f»alze"), ex.context());
  }

  @Test
  void test_missing_colon() {
    final var ex = expectError("{\"a\" 1}", ji -> ji.skipUntil("a"));
    assertTrue(ex.getMessage().contains("expected :"), ex.getMessage());
    assertTrue(ex.context().contains("{\"a\" 1"), ex.context());
  }

  @Test
  void test_unterminated_string() {
    final var ex = expectError("{\"a\":\"abc", ji -> ji.skipUntil("a").readString());
    assertTrue(ex.getMessage().contains("incomplete string"), ex.getMessage());
    // marker placement differs by source here — byte-backed iterators stop
    // at end of input ({"a":"abc»), the char-backed one at the string start
    // ({"a":"»abc) — so only assert the window shows the unterminated string
    assertTrue(ex.context().replace("»", "").contains("\"abc"), ex.context());
  }

  @Test
  void test_long_document_windows_not_dumps() {
    // error near the start of a long document: the window must not swallow
    // the whole tail, and must not include the far end of the buffer
    final var tail = "\"padding\":\"" + "x".repeat(200) + "\"}";
    final var ex = expectError("{\"a\" 1," + tail, ji -> ji.skipUntil("a"));
    assertTrue(ex.getMessage().contains("expected :"), ex.getMessage());
    assertTrue(ex.context().endsWith("…"), ex.context());
    assertFalse(ex.context().startsWith("…"), ex.context());
    assertTrue(ex.context().length() <= 2 + 1 + 2 * BaseJsonIterator.ERROR_CONTEXT_RADIUS, ex.context());
    assertFalse(ex.getMessage().contains("xxxxxxxx\"}"), ex.getMessage());
  }

  @Test
  void test_error_deep_in_long_document_is_ellipsized_on_both_sides() {
    final var pad = "\"p\":\"" + "x".repeat(150) + "\",";
    final var json = "{" + pad + "\"flag\":\"yes\"," + pad.replace("\"p\"", "\"q\"") + "\"end\":1}";
    final var ex = expectError(json, ji -> ji.skipUntil("flag").readBoolean());
    assertEquals("readBoolean", ex.op());
    assertTrue(ex.context().startsWith("…"), ex.context());
    assertTrue(ex.context().endsWith("…"), ex.context());
    // the relevant field is in the window; the document extremes are not
    assertTrue(ex.context().contains("\"flag\":"), ex.context());
    assertFalse(ex.context().contains("\"end\""), ex.context());
    assertFalse(ex.context().contains("{\"p\""), ex.context());
  }

  // --- good JSON, bad parser ---

  @Test
  void test_open_array_on_object() {
    final var ex = expectError("{\"a\":1}", JsonIterator::openArray);
    assertEquals("openArray", ex.op());
    assertTrue(ex.getMessage().contains("expected '[' but found: {"), ex.getMessage());
    // the offending '{' sits immediately before the marker
    assertEquals("{»\"a\":1}", ex.context());
    assertEquals(1, ex.offset());
  }

  @Test
  void test_read_string_on_number() {
    final var ex = expectError("[42]", ji -> {
      ji.readArray();
      ji.readString();
    });
    assertEquals("readString", ex.op());
    assertTrue(ex.getMessage().contains("expected string or null, but 4"), ex.getMessage());
    assertEquals("[4»2]", ex.context());
  }

  @Test
  void test_read_boolean_on_string() {
    final var ex = expectError("{\"flag\":\"yes\"}", ji -> ji.skipUntil("flag").readBoolean());
    assertEquals("readBoolean", ex.op());
    assertTrue(ex.getMessage().contains("expected t or f"), ex.getMessage());
    assertTrue(ex.context().contains("\"flag\":\"»yes\""), ex.context());
  }

  @Test
  void test_close_obj_inside_array() {
    final var ex = expectError("[1]", JsonIterator::closeObj);
    assertEquals("closeObj", ex.op());
    assertTrue(ex.getMessage().contains("expected '}'"), ex.getMessage());
    assertEquals("[»1]", ex.context());
  }

  @Test
  void test_unmatched_discriminator_value() {
    final var matcher = FieldMatcher.of("fixed", "prefixed");
    final var ex = expectError(
        "{\"kind\":\"unknownKind\",\"x\":1}",
        ji -> ji.skipUntil("kind").matchStringOrThrow(matcher)
    );
    assertEquals("matchStringOrThrow", ex.op());
    assertTrue(ex.getMessage().contains("unmatched value \"unknownKind\""), ex.getMessage());
    // the unmatched value sits immediately before the marker
    assertTrue(ex.context().contains("unknownKind\"»"), ex.context());
  }

  @Test
  void test_current_buffer_windows_position() {
    final var ji = factory.create("{\"kind\":\"unknownKind\",\"x\":1}");
    ji.skipUntil("kind").skip();
    assertEquals("offset: 21, context: {\"kind\":\"unknownKind\"»,\"x\":1}", ji.currentBuffer());

    // long documents window instead of dumping
    final var longJi = factory.create("{\"a\":\"" + "x".repeat(300) + "\",\"b\":2}");
    longJi.skipUntil("b");
    final var described = longJi.currentBuffer();
    assertTrue(described.contains("…"), described);
    assertTrue(described.contains("\"b\":»2}"), described);
    assertFalse(described.contains("{\"a\""), described);
  }

  @Test
  void test_plain_json_exception_carries_no_parse_context() {
    final var ex = new JsonException("not from a parse failure");
    assertNull(ex.op());
    assertNull(ex.context());
    assertEquals(-1, ex.offset());
  }
}
