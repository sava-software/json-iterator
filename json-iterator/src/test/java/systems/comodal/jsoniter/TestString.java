package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import systems.comodal.jsoniter.factories.JsonIteratorFactory;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.Random;

import static java.lang.String.format;
import static org.junit.jupiter.api.Assertions.*;

final class TestString {

  private static final Base64.Encoder BASE64_ENCODER = Base64.getEncoder();

  @ParameterizedTest
  @MethodSource("systems.comodal.jsoniter.TestFactories#factories")
  void test_ascii_string(final JsonIteratorFactory factory) {
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
  void testEscapeQuotes() {
    final var escaped = """
        {\\"hello\\": \\"world\\"}""";

    var nestedJson = """
        {"hello": "world"}""";
    assertEquals(escaped, JIUtil.escapeQuotes(nestedJson));
    assertEquals(escaped, JIUtil.escapeQuotesChecked(nestedJson));

    nestedJson = """
        {"hello": "\\"world\\""}""";
    assertEquals("""
        {\\"hello\\": \\"\\"world\\"\\"}""", JIUtil.escapeQuotes(nestedJson)
    );
    assertEquals("""
        {\\"hello\\": \\"\\"world\\"\\"}""", JIUtil.escapeQuotesChecked(nestedJson)
    );

    assertSame(escaped, JIUtil.escapeQuotes(escaped));
    assertSame(escaped, JIUtil.escapeQuotesChecked(escaped));
  }

  @Test
  void testEscapeQuotesRemoveNewLines() {
    var json = "{\"hello\":\n \"world\"\r}";
    var expected = "{\\\"hello\\\": \\\"world\\\"}";
    assertEquals(expected, JIUtil.escapeQuotesRemoveNewLines(json));
    assertEquals(expected, JIUtil.escapeQuotesRemoveNewLinesChecked(json));

    json = "{\"hello\": \"world\"}";
    expected = "{\\\"hello\\\": \\\"world\\\"}";
    assertEquals(expected, JIUtil.escapeQuotesRemoveNewLines(json));
    assertEquals(expected, JIUtil.escapeQuotesRemoveNewLinesChecked(json));

    json = "{\n\"hello\": \"world\"}";
    assertEquals(expected, JIUtil.escapeQuotesRemoveNewLines(json));
    assertEquals(expected, JIUtil.escapeQuotesRemoveNewLinesChecked(json));

    json = "hello\nworld\r\n";
    expected = "helloworld";
    assertEquals(expected, JIUtil.escapeQuotesRemoveNewLines(json));
    assertEquals(expected, JIUtil.escapeQuotesRemoveNewLinesChecked(json));

    json = "no special characters";
    assertSame(json, JIUtil.escapeQuotesRemoveNewLines(json));
    assertSame(json, JIUtil.escapeQuotesRemoveNewLinesChecked(json));

    json = "\"escaped \\\" quote\" \n \r";
    expected = "\\\"escaped \\\" quote\\\"  ";
    assertEquals(expected, JIUtil.escapeQuotesRemoveNewLines(json));
    assertEquals(expected, JIUtil.escapeQuotesRemoveNewLinesChecked(json));

    json = "\n\"newline before quote\"";
    expected = "\\\"newline before quote\\\"";
    assertEquals(expected, JIUtil.escapeQuotesRemoveNewLines(json));
    assertEquals(expected, JIUtil.escapeQuotesRemoveNewLinesChecked(json));

    json = "  \r  \n  \"spaces and newlines before quote\"";
    expected = "      \\\"spaces and newlines before quote\\\"";
    assertEquals(expected, JIUtil.escapeQuotesRemoveNewLines(json));
    assertEquals(expected, JIUtil.escapeQuotesRemoveNewLinesChecked(json));
  }

  @ParameterizedTest
  @MethodSource("systems.comodal.jsoniter.TestFactories#factories")
  void testRandomBase64Data(final JsonIteratorFactory factory) {
    final var random = new Random();
    var data = new byte[4_096];
    random.nextBytes(data);
    var ji = factory.create(format("{\"data\":\"%s\"}", BASE64_ENCODER.encodeToString(data)));
    assertArrayEquals(data, ji.skipUntil("data").decodeBase64String());

    for (int len = 0; len <= 10; ++len) {
      data = new byte[len];
      random.nextBytes(data);
      ji = factory.create(format("{\"data\":\"%s\"}", BASE64_ENCODER.encodeToString(data)));
      assertArrayEquals(data, ji.skipUntil("data").decodeBase64String());
    }
  }

  @ParameterizedTest
  @MethodSource("systems.comodal.jsoniter.TestFactories#factories")
  void test_escapes_string(final JsonIteratorFactory factory) {
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

  @ParameterizedTest
  @MethodSource("systems.comodal.jsoniter.TestFactories#factories")
  void test_ascii_string_with_escape(final JsonIteratorFactory factory) {
    var json = "\"he\tllo\"";
    var ji = factory.create(json);
    assertEquals("he\tllo", ji.readString());
  }

  @ParameterizedTest
  @MethodSource("systems.comodal.jsoniter.TestFactories#factories")
  void test_utf8_string(final JsonIteratorFactory factory) {
    var ji = factory.create("\"中文\"");
    assertEquals("中文", ji.readString());
  }

  @ParameterizedTest
  @MethodSource("systems.comodal.jsoniter.TestFactories#factories")
  void test_incomplete_escape(final JsonIteratorFactory factory) {
    var ji = factory.create("\"\\");
    assertThrows(JsonException.class, ji::readString);
  }

  @ParameterizedTest
  @MethodSource("systems.comodal.jsoniter.TestFactories#factories")
  void test_surrogate(final JsonIteratorFactory factory) {
    var ji = factory.create("\"\uD83D\uDC4A\"");
    assertEquals("\ud83d\udc4a", ji.readString());
  }

  @ParameterizedTest
  @MethodSource("systems.comodal.jsoniter.TestFactories#factories")
  void test_larger_than_buffer(final JsonIteratorFactory factory) {
    var ji = factory.create("\"0123456789012345678901234567890123\"");
    assertEquals("0123456789012345678901234567890123", ji.readString());
  }

  @ParameterizedTest
  @MethodSource("systems.comodal.jsoniter.TestFactories#factories")
  void test_string_across_buffer(final JsonIteratorFactory factory) {
    var ji = factory.create("\"hello\"\"world\"", 2, 2);
    assertEquals("hello", ji.readString());
    assertEquals("world", ji.readString());
  }

  @Test
  void test_utf8() {
    byte[] bytes = {'"', (byte) 0xe4, (byte) 0xb8, (byte) 0xad, (byte) 0xe6, (byte) 0x96, (byte) 0x87, '"'};
    var ji = JsonIterator.parse(new ByteArrayInputStream(bytes), 2);
    assertEquals("中文", ji.readString());

    ji = JsonIterator.parse(bytes);
    assertEquals("中文", ji.readString());
  }

  @Test
  void test_normal_escape() {
    byte[] bytes = {'"', (byte) '\\', (byte) 't', '"'};
    var ji = JsonIterator.parse(new ByteArrayInputStream(bytes), 2);
    assertEquals("\t", ji.readString());

    ji = JsonIterator.parse(bytes);
    assertEquals("\t", ji.readString());
  }

  @Test
  void test_unicode_escape() {
    byte[] bytes = {'"', (byte) '\\', (byte) 'u', (byte) '4', (byte) 'e', (byte) '2', (byte) 'd', '"'};
    var ji = JsonIterator.parse(new ByteArrayInputStream(bytes), 2);
    assertEquals("中", ji.readString());

    ji = JsonIterator.parse(bytes);
    assertEquals("中", ji.readString());
  }

  @ParameterizedTest
  @MethodSource("systems.comodal.jsoniter.TestFactories#factories")
  void test_null_string(final JsonIteratorFactory factory) {
    var ji = factory.create("null");
    assertNull(ji.readString());
  }

  @ParameterizedTest
  @MethodSource("systems.comodal.jsoniter.TestFactories#factories")
  void test_long_string(final JsonIteratorFactory factory) {
    var ji = factory.create("\"[\\\"LL\\\",\\\"MM\\\\\\/LW\\\",\\\"JY\\\",\\\"S\\\",\\\"C\\\",\\\"IN\\\",\\\"ME \\\\\\/ LE\\\"]\"");
    assertEquals("[\"LL\",\"MM\\/LW\",\"JY\",\"S\",\"C\",\"IN\",\"ME \\/ LE\"]", ji.readString());
  }
}
