package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class TestJIUtil {

  @Test
  void testMinify() {
    assertMinifies("{\"a\":1,\"b\":[1,2]}", " { \"a\" : 1 ,\n\t\"b\" : [ 1 , 2 ] } ");
    // whitespace inside strings is preserved, including around escaped quotes
    assertMinifies("{\"a b\":\"c \\\" d\"}", "{ \"a b\" : \"c \\\" d\" }");
    assertMinifies("{\"中 文\":\"a\\tb c\"}", " { \"中 文\" : \"a\\tb c\" } ");
    assertMinifies("", "  \n\t\r ");
    assertMinifies("", "");

    // idempotent
    final var minified = JIUtil.minify(" [ 1 , \"a b\" , true ] ".getBytes());
    org.junit.jupiter.api.Assertions.assertArrayEquals(minified, JIUtil.minify(minified));

    // escapes and string boundaries swept across vector-width and block boundaries
    for (int prefix = 0; prefix <= 70; ++prefix) {
      final var pad = "x".repeat(prefix);
      assertMinifies("{\"" + pad + " \\\" a\":[1,2]}", "{ \"" + pad + " \\\" a\" : [ 1 , 2 ] }");
      assertMinifies("{\"" + pad + "\":\"" + pad + " end\"}", "  {  \"" + pad + "\"  :  \"" + pad + " end\"  }  ");
    }

    // large pretty-printed document vs the scalar reference
    final var sb = new StringBuilder(1 << 16).append("{\n");
    for (int i = 0; i < 500; ++i) {
      if (i > 0) {
        sb.append(",\n");
      }
      sb.append("  \"field").append(i).append("\" : {\n    \"s\" : \"v a l \\\\ \\\" ").append(i)
          .append("\",\n    \"n\" : ").append(i).append("\n  }");
    }
    final var doc = sb.append("\n}").toString().getBytes();
    org.junit.jupiter.api.Assertions.assertArrayEquals(scalarMinify(doc), JIUtil.minify(doc));

    org.junit.jupiter.api.Assertions.assertThrows(JsonException.class, () -> JIUtil.minify("{\"a\":\"unclosed}".getBytes()));
  }

  private static void assertMinifies(final String expected, final String json) {
    final var input = json.getBytes();
    assertEquals(expected, new String(JIUtil.minify(input)));
    org.junit.jupiter.api.Assertions.assertArrayEquals(scalarMinify(input), JIUtil.minify(input), json);
  }

  /// Scalar reference minifier used as a correctness oracle.
  private static byte[] scalarMinify(final byte[] src) {
    final byte[] out = new byte[src.length];
    boolean inString = false;
    int d = 0;
    for (int i = 0; i < src.length; ++i) {
      final byte c = src[i];
      if (inString) {
        out[d++] = c;
        if (c == '\\') {
          out[d++] = src[++i];
        } else if (c == '"') {
          inString = false;
        }
      } else if (c == '"') {
        inString = true;
        out[d++] = c;
      } else if (c != ' ' && c != '\n' && c != '\t' && c != '\r') {
        out[d++] = c;
      }
    }
    return java.util.Arrays.copyOf(out, d);
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
}
