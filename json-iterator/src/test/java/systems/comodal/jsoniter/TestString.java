package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.FieldSource;
import systems.comodal.jsoniter.factories.CharArray;
import systems.comodal.jsoniter.factories.IndexedCharArray;
import systems.comodal.jsoniter.factories.JsonIteratorFactory;

import java.util.Base64;
import java.util.Random;

import static java.lang.String.format;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
  void test_incomplete_escape() {
    // Indexed factories detect the unclosed string when the index is built,
    // scalar iterators when it is read.
    assertThrows(JsonException.class, () -> factory.create("\"\\").readString());
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
    // Sweep escapes, multi-byte characters, and unicode escapes across vector
    // width boundaries, reading via readString and via applyChars, which
    // exercise independent scan/copy paths.
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
    // CharsJsonIterator has never decoded \\uXXXX escapes; its escape handling
    // only strips backslashes.
    assumeTrue(factory != CharArray.INSTANCE && factory != IndexedCharArray.INSTANCE,
        "CharsJsonIterator does not decode unicode escapes");
    for (int prefix = 0; prefix <= 70; prefix += 3) {
      final var pad = "x".repeat(prefix);
      final var json = '"' + pad + "\\u4e2d\\ud83d\\udc4a tail with ascii run afterwards 0123456789\"";
      final var expected = pad + "中👊 tail with ascii run afterwards 0123456789";
      assertEquals(expected, factory.create(json).readString(), "prefix=" + prefix);
      assertEquals(expected, factory.create(json).applyChars(String::new), "prefix=" + prefix);
    }
  }
}
