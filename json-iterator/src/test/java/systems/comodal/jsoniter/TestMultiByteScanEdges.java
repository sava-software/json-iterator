package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// Byte-level edges of the multibyte scan paths that valid Java Strings
/// cannot express: invalid UTF-8 leads, out-of-range code points, truncated
/// sequences, and char-buffer growth mid-string. Byte-sourced only —
/// [BytesJsonIterator] is the sole reader of raw UTF-8.
final class TestMultiByteScanEdges {

  private static byte[] quoted(final int... content) {
    final byte[] doc = new byte[content.length + 2];
    doc[0] = '"';
    for (int i = 0; i < content.length; ++i) {
      doc[i + 1] = (byte) content[i];
    }
    doc[doc.length - 1] = '"';
    return doc;
  }

  @Test
  void test_code_point_above_unicode_range_rejects() {
    // F4 90 80 80 is exactly U+110000 — one past the last plane — and the
    // variants put nonzero bits in each continuation position so every term
    // of the 4-byte accumulation decides a verdict; read and skip must reject
    for (final int[] tooBig : new int[][]{
        {0xF4, 0x90, 0x80, 0x80}, {0xF4, 0x90, 0x80, 0x81}, {0xF4, 0x90, 0x81, 0x80}, {0xF4, 0x91, 0x80, 0x80}}) {
      final byte[] doc = quoted(tooBig);
      final var label = java.util.Arrays.toString(tooBig);
      assertThrows(JsonException.class, () -> JsonIterator.parse(doc).readString(), label);
      assertThrows(JsonException.class, () -> JsonIterator.parse(doc).skip(), "skip " + label);
    }
    // U+10FFFF, one below, is accepted by both
    final byte[] max = quoted(0xF4, 0x8F, 0xBF, 0xBF);
    assertEquals("􏿿", JsonIterator.parse(max).readString());
    JsonIterator.parse(max).skip();
  }

  @Test
  void test_overlong_four_byte_below_supplementary_range() {
    // F0 8F BF BF encodes U+FFFF in 4 bytes: below the surrogate-split
    // threshold, so it must decode as the single char, not a split pair
    final byte[] doc = quoted(0xF0, 0x8F, 0xBF, 0xBF);
    assertEquals("￿", JsonIterator.parse(doc).readString());
    JsonIterator.parse(doc).skip();
  }

  @Test
  void test_invalid_lead_bytes_reject() {
    for (final int lead : new int[]{0xF8, 0xFC, 0xFF}) {
      final byte[] doc = quoted(lead, 0x80, 0x80, 0x80);
      assertThrows(JsonException.class, () -> JsonIterator.parse(doc).readString(), "lead=" + lead);
      assertThrows(JsonException.class, () -> JsonIterator.parse(doc).skip(), "skip lead=" + lead);
    }
  }

  @Test
  void test_truncated_sequences_reject() {
    // a lead byte whose continuation bytes run off the end of the buffer, at
    // each sequence length, unterminated (no closing quote)
    for (final int[] content : new int[][]{
        {0xC3}, {0xE4}, {0xE4, 0xB8}, {0xF0}, {0xF0, 0x9F}, {0xF0, 0x9F, 0x98}}) {
      final byte[] doc = new byte[content.length + 1];
      doc[0] = '"';
      for (int i = 0; i < content.length; ++i) {
        doc[i + 1] = (byte) content[i];
      }
      final var label = java.util.Arrays.toString(content);
      assertThrows(JsonException.class, () -> JsonIterator.parse(doc).readString(), label);
      assertThrows(JsonException.class, () -> JsonIterator.parse(doc).skip(), "skip " + label);
    }
  }

  @Test
  void test_char_buffer_growth_through_surrogate_split() {
    // a tiny char buffer forces doubleReusableCharBuffer on both halves of a
    // surrogate pair; content must survive every grow
    final var expected = "😀x😀yz😀";
    final byte[] doc = ('"' + expected + '"').getBytes();
    assertEquals(expected, JsonIterator.parse(doc, 2).readString());
    // two ascii chars first: the buffer is exactly full when the HIGH
    // surrogate write needs a grow, exercising that arm specifically
    final var highGrow = "xx😀";
    assertEquals(highGrow, JsonIterator.parse(('"' + highGrow + '"').getBytes(), 2).readString());
    // and via the whole-buffer parse overloads with a sub-range
    final byte[] padded = ("xx\"" + expected + "\"yy").getBytes();
    assertEquals(expected, JsonIterator.parse(padded, 2, padded.length - 2, 2).readString());
  }
}
