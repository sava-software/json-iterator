package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class TestJIUtil {

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
}
