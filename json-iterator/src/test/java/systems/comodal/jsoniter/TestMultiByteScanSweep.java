package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.FieldSource;
import systems.comodal.jsoniter.factories.JsonIteratorFactory;

import static org.junit.jupiter.api.Assertions.*;

/// Position sweeps for the multibyte string scan paths
/// ([BytesJsonIterator]'s word-at-a-time quote/escape search and its
/// multibyte decode), walking 2-, 3-, and 4-byte UTF-8 sequences across every
/// offset in a word window. The skip sweeps assert the position *after* the
/// string by reading a trailing value — a scan that misjudges the closing
/// quote produces a wrong next read, not just a wrong string.
@ParameterizedClass
@FieldSource("systems.comodal.jsoniter.TestFactories#FACTORIES")
final class TestMultiByteScanSweep {

  // 2-, 3-, and 4-byte UTF-8 sequences
  private static final String[] MULTIBYTE = {"é", "中", "😀"};

  private final JsonIteratorFactory factory;

  TestMultiByteScanSweep(final JsonIteratorFactory factory) {
    this.factory = factory;
  }

  @Test
  void test_read_string_multibyte_positions() {
    for (final var mb : MULTIBYTE) {
      for (int prefix = 0; prefix <= 40; ++prefix) {
        final var expected = "x".repeat(prefix) + mb + "tail" + mb;
        final var json = '"' + expected + '"';
        assertEquals(expected, factory.create(json).readString(), () -> "readString " + json);
        assertEquals(expected, factory.create(json).applyChars(String::new), () -> "applyChars " + json);
      }
    }
  }

  @Test
  void test_read_string_multibyte_at_closing_quote() {
    // the multibyte sequence is the final content before the closing quote,
    // and the closing quote is the final buffer byte
    for (final var mb : MULTIBYTE) {
      for (int prefix = 0; prefix <= 40; ++prefix) {
        final var expected = "x".repeat(prefix) + mb;
        assertEquals(expected, factory.create('"' + expected + '"').readString(), "prefix=" + prefix);
      }
    }
  }

  @Test
  void test_skip_string_multibyte_positions() {
    for (final var mb : MULTIBYTE) {
      for (int prefix = 0; prefix <= 40; ++prefix) {
        final var json = "[\"" + "x".repeat(prefix) + mb + "tail\", 7]";
        final var ji = factory.create(json);
        assertTrue(ji.readArray());
        ji.skip();
        assertTrue(ji.readArray(), () -> "skip overran " + json);
        assertEquals(7, ji.readInt(), () -> "skip misplaced " + json);
        assertFalse(ji.readArray());
      }
    }
  }

  @Test
  void test_skip_string_multibyte_and_escapes_positions() {
    for (final var mb : MULTIBYTE) {
      for (int prefix = 0; prefix <= 40; ++prefix) {
        // escaped quote inside the string, multibyte on both sides of it
        var json = "[\"" + "x".repeat(prefix) + mb + "\\\"" + mb + "tail\", 7]";
        var ji = factory.create(json);
        assertTrue(ji.readArray());
        ji.skip();
        assertTrue(ji.readArray(), "escaped-quote skip " + json);
        assertEquals(7, ji.readInt());

        // escaped backslash as the final content: the closing quote follows
        // an even escape run and must terminate the string
        json = "[\"" + "x".repeat(prefix) + mb + "\\\\\", 7]";
        ji = factory.create(json);
        assertTrue(ji.readArray());
        ji.skip();
        assertTrue(ji.readArray(), "trailing-escape skip " + json);
        assertEquals(7, ji.readInt());
      }
    }
  }

  @Test
  void test_read_string_multibyte_and_escapes_positions() {
    for (final var mb : MULTIBYTE) {
      for (int prefix = 0; prefix <= 40; ++prefix) {
        final var json = '"' + "x".repeat(prefix) + mb + "\\\"" + mb + "\\\\end\"";
        final var expected = "x".repeat(prefix) + mb + '"' + mb + "\\end";
        assertEquals(expected, factory.create(json).readString(), "prefix=" + prefix);
      }
    }
  }

  @Test
  void test_supplementary_plane_boundaries() {
    // U+10000 and U+10FFFF: the plane-bit extremes of 4-byte sequences. The
    // low plane catches the surrogate-split threshold; the high one has
    // nonzero bits in every position of the 4-byte accumulation, so each
    // shift term is content-observable.
    for (final var value : new String[]{"𐀀", "􏿿"}) {
      for (int prefix = 0; prefix <= 8; ++prefix) {
        final var expected = "x".repeat(prefix) + value + "t" + value;
        assertEquals(expected, factory.create('"' + expected + '"').readString(), "prefix=" + prefix);
        // and through the skip path, position-asserted
        final var ji = factory.create("[\"" + expected + "\", 7]");
        assertTrue(ji.readArray());
        ji.skip();
        assertTrue(ji.readArray());
        assertEquals(7, ji.readInt());
      }
    }
  }

  @Test
  void test_unicode_escape_surrogate_validation() {
    // valid pairs decode (and skip) cleanly; a high surrogate followed by a
    // non-low escape, or a lone low surrogate, must reject on read AND skip
    assertEquals("𐀀", factory.create("\"\\uD800\\uDC00\"").readString());
    assertEquals("􏿿", factory.create("\"\\uDBFF\\uDFFF\"").readString());
    factory.create("[\"\\uD800\\uDC00\", 7]").openArray().skip();

    for (final var bad : new String[]{"\"\\uD800\\u0041\"", "\"\\uDC00\""}) {
      assertThrows(JsonException.class, () -> factory.create(bad).readString(), bad);
      assertThrows(JsonException.class, () -> factory.create(bad).skip(), "skip " + bad);
    }
  }

  @Test
  void test_truncated_escapes_and_multibyte_reject() {
    // unterminated backslash-u escapes at every truncation length, on read
    // and skip (spelled out: a literal backslash-u in this comment would
    // itself trip javac's unicode-escape preprocessing)
    for (final var truncated : new String[]{"\"\\u", "\"\\uD", "\"\\uD8", "\"\\uD80", "\"\\uD800", "\"\\"}) {
      assertThrows(JsonException.class, () -> factory.create(truncated).readString(), truncated);
      assertThrows(JsonException.class, () -> factory.create(truncated).skip(), "skip " + truncated);
    }
    // unterminated string ending in a multibyte char (no closing quote)
    for (final var mb : MULTIBYTE) {
      final var truncated = '"' + "pad" + mb;
      assertThrows(JsonException.class, () -> factory.create(truncated).readString(), truncated);
      assertThrows(JsonException.class, () -> factory.create(truncated).skip(), "skip " + truncated);
    }
  }
}
