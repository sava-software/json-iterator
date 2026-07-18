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

  @Test
  void testEscapeQuotesCheckedLeadingQuote() {
    // A quote at index 0 takes the dedicated firstUnescapedQuote == 0 branch.
    assertEquals("\\\"abc\\\"def", JIUtil.escapeQuotesChecked("\"abc\"def"));
    assertEquals("\\\"abc\\\"def", JIUtil.escapeQuotes("\"abc\"def"));
  }

  @Test
  void testEscapeQuotesLeadingQuoteOnlySpecial() {
    // Regression: when the ONLY quote sits at index 0, the end-of-scan
    // "nothing changed" check used to conflate with the flushed leading
    // escape and returned the input unescaped.
    assertEquals("\\\"abc", JIUtil.escapeQuotesChecked("\"abc"));
    assertEquals("\\\"abc", JIUtil.escapeQuotes("\"abc"));
    assertEquals("\\\"", JIUtil.escapeQuotes("\""));
    assertEquals("\\\"", JIUtil.escapeQuotesChecked("\""));
    assertEquals("\\\"abc", JIUtil.escapeQuotesRemoveNewLinesChecked("\"abc"));
    assertEquals("\\\"", JIUtil.escapeQuotesRemoveNewLines("\""));
  }

  @Test
  void testEscapeQuotesCheckedEscapedQuotes() {
    // A lone escaped quote is a no-op: the checked scan must return the same
    // instance, including when the backslash run starts at index 0 and when
    // the escaped quote is the final character.
    final var escapedOnly = "\\\"";
    assertSame(escapedOnly, JIUtil.escapeQuotesChecked(escapedOnly));
    final var escapedTail = "ab\\\"";
    assertSame(escapedTail, JIUtil.escapeQuotesChecked(escapedTail));
    // The public variant treats an already-escaped quote the same way.
    final var escapedMid = "a\\\"b";
    assertSame(escapedMid, JIUtil.escapeQuotes(escapedMid));

    // An escaped quote (backslash at index 0) followed by an unescaped quote:
    // only the unescaped one gains a backslash.
    assertEquals("\\\"a\\\"b", JIUtil.escapeQuotesChecked("\\\"a\"b"));
    assertEquals("a\\\"b\\\"c", JIUtil.escapeQuotesChecked("a\\\"b\"c"));
  }

  @Test
  void testEscapeQuotesRemoveNewLinesCheckedQuoteBeforeBackslash() {
    // First special char is an unescaped quote whose *following* char is a
    // backslash: the escape check must look backwards, not forwards.
    assertEquals("a\\\"\\x", JIUtil.escapeQuotesRemoveNewLinesChecked("a\"\\x"));
    assertEquals("a\\\"\\x", JIUtil.escapeQuotesRemoveNewLines("a\"\\x"));
  }

  @Test
  void testFieldHashCode() {
    // 31-based ascii hash over the [from, to) window; chars above 0xff are
    // masked to their low byte.
    final char[] value = {'a', 'é', 'z', 'q'};
    assertEquals(31 * (31 * 'a' + 0xe9) + 'z', JIUtil.fieldHashCode(value, 0, 3));
    assertEquals(31 * 0xe9 + 'z', JIUtil.fieldHashCode(value, 1, 3));
    assertEquals(0, JIUtil.fieldHashCode(value, 2, 2));
    assertEquals(0x100 & 0xff, JIUtil.fieldHashCode(new char[]{'Ā', 'x'}, 0, 1));
  }

  @Test
  void testFieldCompare() {
    assertEquals(0, JIUtil.fieldCompare("ab", new char[]{'a', 'b', 'x'}, 0, 2));
    assertEquals(0, JIUtil.fieldCompare("bc", new char[]{'a', 'b', 'c', 'x'}, 1, 2));
    // Length mismatches short-circuit to len - field.length().
    assertEquals(-1, JIUtil.fieldCompare("abc", new char[]{'a', 'b', 'x'}, 0, 2));
    assertEquals(1, JIUtil.fieldCompare("ab", new char[]{'a', 'b', 'c', 'x'}, 0, 3));
    assertEquals(-3, JIUtil.fieldCompare("abcd", new char[]{'a'}, 0, 1));
    // Equal lengths compare positionally, returning the first difference.
    assertEquals(Character.compare('b', 'a'), JIUtil.fieldCompare("ab", new char[]{'b', 'b', 'q'}, 0, 2));
    assertEquals(Character.compare('x', 'y'), JIUtil.fieldCompare("ay", new char[]{'a', 'x', 'q'}, 0, 2));
  }

  @Test
  void testCompileReplacePattern() {
    assertEquals(0x2222222222222222L, JIUtil.compileReplacePattern((byte) '"'));
    assertEquals(0x5C5C5C5C5C5C5C5CL, JIUtil.compileReplacePattern((byte) '\\'));
    // A byte with the sign bit set must not sign-extend across the pattern.
    assertEquals(0xABABABABABABABABL, JIUtil.compileReplacePattern((byte) 0xAB));
    assertEquals(0L, JIUtil.compileReplacePattern((byte) 0));
    assertEquals(-1L, JIUtil.compileReplacePattern((byte) 0xFF));
  }
}
