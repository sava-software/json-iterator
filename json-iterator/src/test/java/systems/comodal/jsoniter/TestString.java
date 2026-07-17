package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.FieldSource;
import systems.comodal.jsoniter.factories.JsonIteratorFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Random;

import static java.lang.String.format;
import static org.junit.jupiter.api.Assertions.*;

@ParameterizedClass
@FieldSource("systems.comodal.jsoniter.TestFactories#FACTORIES")
final class TestString {

  private static final Base64.Encoder BASE64_ENCODER = Base64.getEncoder();

  private final JsonIteratorFactory factory;

  TestString(final JsonIteratorFactory factory) {
    this.factory = factory;
  }

  @Test
  void test_ascii_string() {
    var ji = factory.create("\"hello\"\"world\"");
    assertEquals("hello", ji.readString());
    assertEquals("world", ji.readString());


    final var hello = "hello".getBytes();
    final var world = "world".getBytes();
    ji = factory.create(format("\"%s\"\"%s\"", BASE64_ENCODER.encodeToString(hello), BASE64_ENCODER.encodeToString(world)));
    assertArrayEquals(hello, ji.decodeBase64String());
    assertArrayEquals(world, ji.decodeBase64String());
  }

  @Test
  void testRandomBase64Data() {
    final long seed = new Random().nextLong();
    final var random = new Random(seed);
    var data = new byte[4_096];
    random.nextBytes(data);
    var ji = factory.create(format("{\"data\":\"%s\"}", BASE64_ENCODER.encodeToString(data)));
    assertArrayEquals(data, ji.skipUntil("data").decodeBase64String(), "seed=" + seed);

    for (int len = 0; len <= 10; ++len) {
      data = new byte[len];
      random.nextBytes(data);
      ji = factory.create(format("{\"data\":\"%s\"}", BASE64_ENCODER.encodeToString(data)));
      assertArrayEquals(data, ji.skipUntil("data").decodeBase64String(), "seed=" + seed);
    }
  }

  @Test
  void testDecodeBase64Robustness() {
    // a JSON null decodes to null on every source type
    assertNull(factory.create("{\"data\":null}").skipUntil("data").decodeBase64String());

    // Bare documents, so nothing follows the string: the closing quote walks
    // across every position relative to the byte path's 8-byte scan words and
    // the end of the buffer.
    final long seed = new Random().nextLong();
    final var random = new Random(seed);
    for (int len = 0; len <= 18; ++len) {
      final var data = new byte[len];
      random.nextBytes(data);
      final var json = '"' + BASE64_ENCODER.encodeToString(data) + '"';
      assertArrayEquals(data, factory.create(json).decodeBase64String(), "seed=" + seed + " len=" + len);
    }

    // JSON-escaped '/' (e.g. PHP's json_encode escapes it by default; '/' is
    // in the base64 alphabet) decodes on every source type, through both the
    // short scalar path and the word-at-a-time path
    final var slashes = new byte[]{-1, -1, -1, -1, -1, -1, -1, -1, -1}; // encodes to "////////////"
    final var escaped = BASE64_ENCODER.encodeToString(slashes).replace("/", "\\/");
    assertArrayEquals(slashes, factory.create('"' + escaped + '"').decodeBase64String());
    assertArrayEquals(slashes, factory.create("{\"data\":\"" + escaped + "\"}").skipUntil("data").decodeBase64String());
    final var shortSlash = new byte[]{-1}; // encodes to "/w=="
    assertArrayEquals(shortSlash, factory.create("\"\\/w==\"").decodeBase64String());

    // illegal content throws on every source type
    assertThrows(IllegalArgumentException.class, () -> factory.create("\"ab@cd\"").decodeBase64String());
    assertThrows(IllegalArgumentException.class, () -> factory.create("\"ab\\ncd\"").decodeBase64String());
    assertThrows(IllegalArgumentException.class, () -> factory.create("\"ab\\\"cd\"").decodeBase64String());

    // an InputStream source, read fully upfront
    final var big = new byte[4_096];
    random.nextBytes(big);
    final var doc = format("{\"data\":\"%s\"}", BASE64_ENCODER.encodeToString(big)).getBytes(StandardCharsets.US_ASCII);
    final var ji = JsonIterator.parse(new ByteArrayInputStream(doc), 64);
    assertArrayEquals(big, ji.skipUntil("data").decodeBase64String(), "seed=" + seed);
  }

  @Test
  void test_escapes_string() {
    var ji = factory.create("\"even" + "\\".repeat(42) + '"');
    assertEquals("even" + "\\".repeat(21), ji.readString());

    ji = factory.create("\"odd" + "\\".repeat(11) + "\"\"");
    assertEquals("odd" + "\\".repeat(5) + '"', ji.readString());

    ji = factory.create("\"even\\\\\"");
    assertEquals("even\\", ji.readString());

    ji = factory.create("\"odd\\\\\\\"\"");
    assertEquals("odd\\\"", ji.readString());

    ji = factory.create("\"odd\\\"\"");
    assertEquals("odd\"", ji.readString());

    // escape codes are decoded, not just backslash-stripped
    ji = factory.create("\"a\\tb\\nc\\rd\\fe\\bf\\/g\"");
    assertEquals("a\tb\nc\rd\fe\bf/g", ji.readString());

    // unicode escapes, including a surrogate pair
    ji = factory.create("\"\\u4e2d\\u6587 \\ud83d\\ude0a \\u0041\"");
    assertEquals("中文 😊 A", ji.readString());

    // escapes reach the IOC char buffer identically
    assertEquals("a\tb", factory.create("\"a\\tb\"").applyChars(String::new));

    // a lone low surrogate and an unknown escape are rejected
    assertThrows(JsonException.class, () -> factory.create("\"\\ude0a\"").readString());
    assertThrows(RuntimeException.class, () -> factory.create("\"a\\xb\"").readString());
  }

  @Test
  void test_invalid_hex_escape_digits() {
    // fuzz regression: JHex faulted with IndexOutOfBoundsException instead of
    // rejecting. The invalid digit may sit in any nibble, exceed 'f' (bounds,
    // not just table lookup), or be a multibyte char — negative as a raw byte
    // on the bytes source and above the table range on the chars source.
    final var invalid = new String[]{
        "\"\\uFFMF\"", "\"\\ug000\"", "\"\\u000z\"", "\"\\uzzzz\"",
        "\"\\u00!0\"", "\"\\u0 41\"", "\"\\u0é41\"", "\"\\u中中中中\""
    };
    for (final var json : invalid) {
      assertThrows(JsonException.class, () -> factory.create(json).readString(), json);
    }
  }

  @Test
  void test_ascii_string_with_escape() {
    var json = "\"he\tllo\"";
    var ji = factory.create(json);
    assertEquals("he\tllo", ji.readString());
  }

  @Test
  void test_utf8_string() {
    var ji = factory.create("\"中文\"");
    assertEquals("中文", ji.readString());
  }

  @Test
  void test_long_utf8_strings() {
    final var values = new String[]{
        "日本語テスト日本語テスト", // no 0x80 / 0x5C bytes at all
        "元野球部マネージャー❤︎…最高の夏をありがとう…", // 0x80 first appears mid-word (twitter.json)
        "中文👊中文👊中文👊", // 4-byte sequences
        "❤︎…" // short enough for the scalar path
    };
    for (final var value : values) {
      for (int pad = 0; pad < 9; ++pad) {
        final var padded = "a".repeat(pad) + value;
        final var json = "{\"data\":\"" + padded + "\",\"want\":42}";
        assertEquals(padded, factory.create(json).skipUntil("data").readString());
        assertEquals(42, factory.create(json).skipUntil("want").readInt());
      }
    }
  }

  @Test
  void test_incomplete_escape() {
    var ji = factory.create("\"\\");
    assertThrows(JsonException.class, ji::readString);
  }

  @Test
  void test_surrogate() {
    var ji = factory.create("\"👊\"");
    assertEquals("👊", ji.readString());
  }

  @Test
  void test_larger_than_buffer() {
    var ji = factory.create("\"0123456789012345678901234567890123\"");
    assertEquals("0123456789012345678901234567890123", ji.readString());
  }

  @Test
  void test_string_across_buffer() {
    var ji = factory.create("\"hello\"\"world\"", 2, 2);
    assertEquals("hello", ji.readString());
    assertEquals("world", ji.readString());
  }

  @Test
  void test_null_string() {
    var ji = factory.create("null");
    assertNull(ji.readString());
  }

  @Test
  void test_long_string() {
    var ji = factory.create("\"[\\\"LL\\\",\\\"MM\\\\\\/LW\\\",\\\"JY\\\",\\\"S\\\",\\\"C\\\",\\\"IN\\\",\\\"ME \\\\\\/ LE\\\"]\"");
    assertEquals("[\"LL\",\"MM\\/LW\",\"JY\",\"S\",\"C\",\"IN\",\"ME \\/ LE\"]", ji.readString());
  }

  @Test
  void test_escape_positions_across_vector_widths() {
    // Sweep escapes, multi-byte characters, and long ascii runs across word
    // boundaries, reading via readString and via applyChars, which exercise
    // independent scan/copy paths.
    for (int prefix = 0; prefix <= 70; prefix += 3) {
      final var pad = "x".repeat(prefix);

      var json = '"' + pad + "\\\"tail of the string 0123456789012345678901234567890123456789\"";
      var expected = pad + "\"tail of the string 0123456789012345678901234567890123456789";
      assertEquals(expected, factory.create(json).readString(), "prefix=" + prefix);
      assertEquals(expected, factory.create(json).applyChars(String::new), "prefix=" + prefix);

      json = '"' + pad + "中文 and more ascii after the multibyte section 01234567890123456789\"";
      expected = pad + "中文 and more ascii after the multibyte section 01234567890123456789";
      assertEquals(expected, factory.create(json).readString(), "prefix=" + prefix);
      assertEquals(expected, factory.create(json).applyChars(String::new), "prefix=" + prefix);

      json = '"' + pad + "y".repeat(150) + '"';
      expected = pad + "y".repeat(150);
      assertEquals(expected, factory.create(json).readString(), "prefix=" + prefix);
      assertEquals(expected, factory.create(json).applyChars(String::new), "prefix=" + prefix);
    }
  }

  @Test
  void test_unicode_escape_positions() {
    for (int prefix = 0; prefix <= 70; prefix += 3) {
      final var pad = "x".repeat(prefix);
      final var json = '"' + pad + "\\u4e2d\\ud83d\\udc4a tail with ascii run afterwards 0123456789\"";
      final var expected = pad + "中👊 tail with ascii run afterwards 0123456789";
      assertEquals(expected, factory.create(json).readString(), "prefix=" + prefix);
      assertEquals(expected, factory.create(json).applyChars(String::new), "prefix=" + prefix);
    }
  }
}
