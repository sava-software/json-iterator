package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// Behavior specific to [IndexedJsonIterator]; everything generic is covered
/// by the factory-parameterized suite, which includes both indexed
/// implementations.
final class TestIndexedJsonIterator {

  @Test
  void test_parse_entry_points() {
    final var json = "{\"hello\": \"world\"}";
    assertEquals("world", IndexedJsonIterator.parse(json).skipUntil("hello").readString());
    assertEquals("world", IndexedJsonIterator.parse(json.getBytes()).skipUntil("hello").readString());
    assertEquals("world", IndexedJsonIterator.parse(json.toCharArray()).skipUntil("hello").readString());

    final var padded = "xx" + json + "yy";
    assertEquals("world", IndexedJsonIterator.parse(padded.getBytes(), 2, 2 + json.length()).skipUntil("hello").readString());
    assertEquals("world", IndexedJsonIterator.parse(padded.toCharArray(), 2, 2 + json.length()).skipUntil("hello").readString());
  }

  @Test
  void test_index_from_plain_iterator() {
    final var json = "{\"skip\":[1,2,3],\"data\":{\"a\":1,\"b\":[true,false]}}";
    // index the whole document
    final var indexed = JsonIterator.parse(json).index();
    assertInstanceOf(IndexedJsonIterator.class, indexed);
    assertSame(indexed, indexed.index());
    assertEquals(1, indexed.skipUntil("data").skipUntil("a").readInt());

    // index a sub-document mid-navigation
    final var ji = JsonIterator.parse(json);
    ji.skipUntil("data");
    final var sub = ji.index();
    assertEquals(1, sub.skipUntil("a").readInt());
    assertTrue(sub.skipUntil("b").openArray().readBoolean());

    // char[] source
    final var charIndexed = JsonIterator.parse(json.toCharArray()).index();
    assertInstanceOf(IndexedJsonIterator.class, charIndexed);
    assertEquals(1, charIndexed.skipUntil("data").skipUntil("a").readInt());
  }

  @Test
  void test_reuse_and_cross_type_resets() {
    final var indexed = IndexedJsonIterator.parse("{\"a\":1}");
    assertEquals(1, indexed.skipUntil("a").readInt());

    var reset = indexed.reset("{\"bb\":\"22\"}".getBytes());
    assertSame(indexed, reset);
    assertEquals("22", reset.skipUntil("bb").readString());

    // resetting a byte-based indexed iterator with chars yields an indexed chars iterator
    reset = indexed.reset("{\"c\":3}".toCharArray());
    assertInstanceOf(IndexedJsonIterator.class, reset);
    assertEquals(3, reset.skipUntil("c").readInt());

    // and back to bytes
    final var bytesAgain = reset.reset("{\"d\":4}".getBytes());
    assertInstanceOf(IndexedJsonIterator.class, bytesAgain);
    assertEquals(4, bytesAgain.skipUntil("d").readInt());
  }

  @Test
  void test_mark_reset() {
    final var ji = IndexedJsonIterator.parse("{\"a\":[1,2,3],\"b\":2}");
    final int mark = ji.mark();
    assertEquals(2, ji.skipUntil("b").readInt());
    ji.reset(mark);
    assertTrue(ji.skipUntil("a").readArray());
    assertEquals(1, ji.readInt());
  }

  @Test
  void test_unclosed_string_detected_at_index_time() {
    assertThrows(JsonException.class, () -> IndexedJsonIterator.parse("\"unclosed"));
    assertThrows(JsonException.class, () -> IndexedJsonIterator.parse("{\"a\":\"unclosed}"));
    assertThrows(JsonException.class, () -> IndexedJsonIterator.parse("{\"a\":\"unclosed}".toCharArray()));
    assertThrows(JsonException.class, () -> IndexedJsonIterator.parse("{\"a\":1}").reset("\"unclosed".getBytes()));
    assertThrows(JsonException.class, () -> JsonIterator.parse("\"unclosed").index());
  }

  @Test
  void test_truncated_document() {
    assertThrows(JsonException.class, () -> IndexedJsonIterator.parse("{\"a\":1").skipUntil("b"));
    assertThrows(JsonException.class, () -> IndexedJsonIterator.parse("[1,2").skipRestOfArray());
    assertThrows(JsonException.class, () -> IndexedJsonIterator.parse("[[1,2").openArray().skip());
  }

  @Test
  void test_utf8_validation() {
    assertEquals("中文👊", IndexedJsonIterator.parseValidating("{\"s\":\"中文👊\"}".getBytes()).skipUntil("s").readString());
    assertEquals(1, IndexedJsonIterator.parseValidating("{\"a\":1}".getBytes()).skipUntil("a").readInt());

    // lone continuation byte
    assertThrows(JsonException.class, () -> IndexedJsonIterator.parseValidating(utf8Doc((byte) 0x80)));
    // overlong 2-byte encoding of '/'
    assertThrows(JsonException.class, () -> IndexedJsonIterator.parseValidating(utf8Doc((byte) 0xC0, (byte) 0xAF)));
    // UTF-16 surrogate U+D800 encoded in UTF-8
    assertThrows(JsonException.class, () -> IndexedJsonIterator.parseValidating(utf8Doc((byte) 0xED, (byte) 0xA0, (byte) 0x80)));
    // code point above U+10FFFF
    assertThrows(JsonException.class, () -> IndexedJsonIterator.parseValidating(utf8Doc((byte) 0xF5, (byte) 0x80, (byte) 0x80, (byte) 0x80)));
    // truncated 3-byte sequence inside the document
    assertThrows(JsonException.class, () -> IndexedJsonIterator.parseValidating(utf8Doc((byte) 0xE4, (byte) 0xB8)));
    // truncated sequence at the very end of the input
    assertThrows(JsonException.class, () -> IndexedJsonIterator.parseValidating(new byte[]{'"', 'x', '"', ' ', (byte) 0xE4, (byte) 0xB8}));
    // validation persists across reuse
    final var ji = IndexedJsonIterator.parseValidating("{\"a\":1}".getBytes());
    assertThrows(JsonException.class, () -> ji.reset(utf8Doc((byte) 0x80)));
    // long valid documents remain valid across chunk boundaries
    final var longDoc = "{\"s\":\"" + "中文👊 mixed with ascii ".repeat(50) + "\"}";
    assertNotNull(IndexedJsonIterator.parseValidating(longDoc.getBytes()).skipUntil("s").readString());

    // without validation, malformed UTF-8 passes through un-checked
    assertNotNull(IndexedJsonIterator.parse(utf8Doc((byte) 0xC0, (byte) 0xAF)).readString());
  }

  private static byte[] utf8Doc(final byte... content) {
    final byte[] doc = new byte[content.length + 2];
    doc[0] = '"';
    System.arraycopy(content, 0, doc, 1, content.length);
    doc[doc.length - 1] = '"';
    return doc;
  }

  @Test
  void test_escaped_and_multibyte_field_names() {
    final var ji = IndexedJsonIterator.parse("{\"a\\tb\":1,\"c\":2}");
    assertEquals("a\tb", ji.readObjField());
    assertEquals(1, ji.readInt());

    assertEquals(2, IndexedJsonIterator.parse("{\"a\\tb\":1,\"c\":2}").skipUntil("c").readInt());
    assertEquals(1, IndexedJsonIterator.parse("{\"a\\tb\":1,\"c\":2}").skipUntil("a\tb").readInt());
    assertEquals(1, IndexedJsonIterator.parse("{\"中文\":1}").skipUntil("中文").readInt());
    assertNull(IndexedJsonIterator.parse("{\"ab\":1}").skipUntil("abc"));
    assertNull(IndexedJsonIterator.parse("{\"abc\":1}").skipUntil("ab"));
  }

  @Test
  void test_large_document() {
    final var sb = new StringBuilder(1 << 16).append('[');
    for (int i = 0; i < 2_000; ++i) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append("{\"i\":").append(i).append(",\"s\":\"value ").append(i).append("\"}");
    }
    final var ji = IndexedJsonIterator.parse(sb.append(']').toString());
    int count = 0;
    long sum = 0;
    while (ji.readArray()) {
      assertEquals("i", ji.readObjField());
      sum += ji.readLong();
      assertEquals("s", ji.readObjField());
      assertEquals("value " + count, ji.readString());
      assertNull(ji.readObjField());
      ++count;
    }
    assertEquals(2_000, count);
    assertEquals(1_999L * 2_000 / 2, sum);
  }
}
