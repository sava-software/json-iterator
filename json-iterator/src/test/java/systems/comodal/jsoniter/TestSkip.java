package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.FieldSource;
import systems.comodal.jsoniter.factories.JsonIteratorFactory;

import static org.junit.jupiter.api.Assertions.*;

@ParameterizedClass
@FieldSource("systems.comodal.jsoniter.TestFactories#FACTORIES")
final class TestSkip {

  private final JsonIteratorFactory factory;

  TestSkip(final JsonIteratorFactory factory) {
    this.factory = factory;
  }

  @Test
  void test_skip_number() {
    var ji = factory.create("[1,2]");
    assertTrue(ji.readArray());
    ji.skip();
    assertTrue(ji.readArray());
    assertEquals(2, ji.readInt());
    assertFalse(ji.readArray());
  }

  @Test
  void test_skip_string() {
    var ji = factory.create("[\"hello\",2]");
    assertTrue(ji.readArray());
    ji.skip();
    assertTrue(ji.readArray());
    assertEquals(2, ji.readInt());
    assertFalse(ji.readArray());
  }

  @Test
  void test_skip_string_streaming() {
    var ji = factory.create("\"hello", 2, 2);
    assertThrows(JsonException.class, ji::skip);

    ji = factory.create("\"hello\"", 2, 2);
    ji.skip();

    ji = factory.create("\"hello\"1", 2, 2);
    ji.skip();
    assertEquals(1, ji.readInt());

    ji = factory.create("\"h\\\"ello\"1", 2, 3);
    ji.skip();
    assertEquals(1, ji.readInt());

    ji = factory.create("\"\\\\\"1", 2, 3);
    ji.skip();
    assertEquals(1, ji.readInt());
  }

  @Test
  void test_skip_string_at_buffer_tail_across_lengths() {
    // The skip word-loop twin of the parse sweeps: the closing quote walks
    // across every 8-byte-word alignment, including the final partial window
    // against the buffer tail.
    for (int len = 0; len <= 40; ++len) {
      final var pad = "x".repeat(len);

      // a trailing field pins the exact position after the skip
      var ji = factory.create("{\"a\":\"" + pad + "\",\"z\":5}");
      assertEquals(5, ji.skipUntil("z").readInt(), "len=" + len);

      // the skipped string ends at the buffer tail
      ji = factory.create("{\"a\":\"" + pad + "\"}");
      assertNull(ji.skipUntil("z"), "len=" + len);

      // unterminated at every alignment: must throw, not scan past the tail
      // or spin on the final window
      final var truncated = factory.create("{\"a\":\"" + pad);
      assertThrows(JsonException.class, () -> truncated.skipUntil("z"), "len=" + len);
    }
  }

  @Test
  void test_skip_surrogate_escapes() {
    // a valid escaped pair skips cleanly and lands exactly after the string
    var ji = factory.create("[\"\\uD801\\uDC37\",7]");
    assertTrue(ji.readArray());
    ji.skip();
    assertTrue(ji.readArray());
    assertEquals(7, ji.readInt());
    assertFalse(ji.readArray());

    // a lone low surrogate rejects; \uDC01 and \uDC10 put the deciding bits
    // in the low hex digits, so the low-digit accumulation must add into the
    // classification range, and \uD801A breaks the pair on the second
    // escape
    for (final var body : new String[]{"\\uDC01", "\\uDC10", "\\uDC00", "\\uD801\\u0041"}) {
      final var bad = factory.create("[\"" + body + "\",7]");
      assertTrue(bad.readArray());
      final var ex = assertThrows(JsonException.class, bad::skip, body);
      assertTrue(ex.getMessage().contains("invalid surrogate"), body + " -> " + ex.getMessage());
    }
  }

  @Test
  void test_skip_truncated_escape_reports_cut_offset() {
    // document cut mid-escape after a valid high surrogate: the skip must
    // report the truncation at the exact offset of the cut — a digit-read
    // cursor that lags the tail checks instead completes the escape from
    // re-read digits and misreports it as "invalid surrogate"
    final var ji = factory.create("[\"\\uD835\\u000");
    assertTrue(ji.readArray());
    final var ex = assertThrows(JsonException.class, ji::skip);
    assertTrue(ex.getMessage().contains("incomplete string, offset: 13"), ex.getMessage());
  }

  @Test
  void test_skip_object() {
    var ji = factory.create("[{\"hello\": {\"world\": \"a\"}},2]");
    assertTrue(ji.readArray());
    ji.skip();
    assertTrue(ji.readArray());
    assertEquals(2, ji.readInt());
    assertFalse(ji.readArray());
  }

  @Test
  void test_skip_array() {
    var ji = factory.create("[ [1,  3] ,2]");
    assertTrue(ji.readArray());
    ji.skip();
    assertTrue(ji.readArray());
    assertEquals(2, ji.readInt());
    assertFalse(ji.readArray());
  }

  @Test
  void test_skip_nested() {
    var ji = factory.create("[ [1, {\"a\": [\"b\"] },  3] ,2]");
    assertTrue(ji.readArray());
    ji.skip();
    assertTrue(ji.readArray());
    assertEquals(2, ji.readInt());
    assertFalse(ji.readArray());
  }

  @Test
  void test_skip_large_containers() {
    // Brackets and escaped quotes inside strings must not confuse the
    // word-at-a-time container skipping.
    final var inner = new StringBuilder("[");
    for (int i = 0; i < 300; ++i) {
      if (i > 0) {
        inner.append(',');
      }
      inner.append("{\"k").append(i).append("\":\"v ] } [ { \\\" ").append(i).append("\",\"n\":[").append(i).append(",[7,8]]}");
    }
    inner.append(']');

    var ji = factory.create("[" + inner + ",2]");
    assertTrue(ji.readArray());
    ji.skip();
    assertTrue(ji.readArray());
    assertEquals(2, ji.readInt());
    assertFalse(ji.readArray());

    ji = factory.create("{\"o\":" + inner + ",\"z\":3}");
    assertEquals(3, ji.skipUntil("z").readInt());
  }

  @Test
  void test_skip_validates_escapes() {
    // fuzz-hardening regression: the char source skipped strings without
    // decoding escapes, accepting documents the byte source rejects
    for (final var value : new String[]{
        "\"a\\uZZZZ\"", "\"a\\u00\", 0]", "\"a\\q\"", "\"\\ude0a\""
    }) {
      assertThrows(JsonException.class, () -> factory.create("[" + value + ", 1]").skipRestOfArray(), value);
    }
    // well-formed escapes and surrogate pairs skip cleanly on every source
    var ji = factory.create("[\"a\\u0041\\ud83d\\ude00\\n\\\\\", 2]");
    assertTrue(ji.readArray());
    ji.skip();
    assertTrue(ji.readArray());
    assertEquals(2, ji.readInt());
    assertFalse(ji.readArray());
  }

  @Test
  void test_truncated_documents_reject() {
    // fuzz-hardening regression: the char source read past its logical tail on
    // truncated input instead of rejecting
    for (final var json : new String[]{
        "", " ", "tru", "fals", "nul", "\"abc", "\"abc\\", "[1,", "{\"a\":", "{\"a\""
    }) {
      assertThrows(JsonException.class, () -> factory.create(json).skip(), "'" + json + "'");
    }
  }
}
