package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class TestJIUtil {

  @Test
  void testEscapeJson() {
    final var clean = "hello world";
    assertSame(clean, JIUtil.escapeJson(clean));
    assertSame("", JIUtil.escapeJson(""));
    // documented contract: null is the caller's problem, not silently defaulted
    assertThrows(NullPointerException.class, () -> JIUtil.escapeJson(null));

    assertEquals("say \\\"hi\\\"", JIUtil.escapeJson("say \"hi\""));
    assertEquals("a\\\\b", JIUtil.escapeJson("a\\b"));
    // raw text: an existing backslash-quote pair is two characters to escape,
    // not an escape sequence to preserve
    assertEquals("a\\\\\\\"b", JIUtil.escapeJson("a\\\"b"));
    assertEquals("trailing\\\\", JIUtil.escapeJson("trailing\\"));

    assertEquals("line\\nbreak", JIUtil.escapeJson("line\nbreak"));
    assertEquals("\\r\\t\\b\\f", JIUtil.escapeJson("\r\t\b\f"));
    assertEquals("nul\\u0000byte", JIUtil.escapeJson("nul\0byte"));
    assertEquals("esc\\u001b", JIUtil.escapeJson("esc\033"));
    // 0x20 is the escape boundary: the last control character is escaped, a
    // space is not. The space has to sit *after* the first escape so the switch
    // judges it — before it, it rides along in the verbatim prefix copy and the
    // boundary goes unasserted (input written octal: a unicode escape would be
    // processed by javac before lexing, comments included)
    assertEquals("\\u001f !", JIUtil.escapeJson("\037 !"));

    // parses back to the original raw value
    final var raw = "q\" b\\ n\n u\001 end";
    final var json = "{\"v\":\"" + JIUtil.escapeJson(raw) + "\"}";
    final var ji = JsonIterator.parse(json);
    ji.skipUntil("v");
    assertEquals(raw, ji.readString());
  }

  @Test
  void testEscapeJsonGrowsPastInitialCapacity() {
    // every character expands to six, well past the len + 8 + (len >> 3) the
    // buffer starts at, so the growth path has to run to produce a correct
    // answer at all
    final var raw = "\001".repeat(64);
    final var escaped = JIUtil.escapeJson(raw);
    assertEquals("\\u0001".repeat(64), escaped);
    assertEquals(64 * 6, escaped.length());

    // growth driven by the trailing span rather than by an escape
    final var trailing = "\001" + "a".repeat(512);
    assertEquals("\\u0001" + "a".repeat(512), JIUtil.escapeJson(trailing));

    // The capacity request has to count the pending span, not just the escape.
    // Sized so that the second escape is the first point needing growth, with a
    // span large enough that dropping it from the request leaves the buffer
    // short: len 15 starts the buffer at 15 + 8 + (15 >> 3) = 24, and at the
    // second escape n = 6 with span = 13, so 6 + 13 + 6 = 25 must grow. A
    // request that subtracts the span instead asks for -1, keeps the 24-char
    // buffer, and runs off the end writing the escape
    final var spanThenEscape = "\001" + "a".repeat(13) + "\001";
    assertEquals("\\u0001" + "a".repeat(13) + "\\u0001", JIUtil.escapeJson(spanThenEscape));
  }

  @Test
  void testEscapeJsonRoundTripsEveryAsciiAtEveryPosition() {
    // property: whatever escapeJson emits parses back to exactly the input. The
    // positions matter as much as the characters — they select the verbatim
    // prefix copy, the span copy between two escapes, and the trailing copy,
    // which are three separate paths through the buffer
    for (int c = 0; c <= 0x7F; ++c) {
      final char ch = (char) c;
      for (final var raw : new String[]{
          String.valueOf(ch),
          "a" + ch,
          ch + "a",
          "a" + ch + "b",
          '"' + String.valueOf(ch) + '"',
          "ab" + ch + "cd" + ch + "ef"
      }) {
        final var ji = JsonIterator.parse("{\"v\":\"" + JIUtil.escapeJson(raw) + "\"}");
        ji.skipUntil("v");
        assertEquals(raw, ji.readString(), () -> "round trip failed for 0x" + Integer.toHexString(ch));
      }
    }
  }

  /// The characters worth walking: both escape-set boundaries (0x1F/0x20 and
  /// the quote/backslash), every short-escape form, and representatives of the
  /// pass-through classes.
  private static final char[] SWEEP_CHARS = {
      '"', '\\', '\n', '\r', '\t', '\b', '\f',
      (char) 0x00, (char) 0x01, (char) 0x1F, (char) 0x20, (char) 0x21, (char) 0x5B, (char) 0x5D,
      (char) 0x7F, 'a', 'é', '中'
  };

  @Test
  void testEscapeJsonEscapesEachCharacterIndependentlyOfPosition() {
    // Property, not a restatement: escaping is per-character, so a character's
    // escape is whatever escapeJson produces for it alone, and placing it at any
    // offset must yield prefix + that + suffix. Every offset splits the verbatim
    // prefix copy, the span copy and the trailing copy differently, which is
    // exactly where an off-by-one hides. Deriving the expectation from the
    // method itself means this test cannot drift from the escape table.
    final int window = 40;
    for (final char c : SWEEP_CHARS) {
      final var escape = JIUtil.escapeJson(String.valueOf(c));
      for (int i = 0; i < window; ++i) {
        final var head = "a".repeat(i);
        final var tail = "a".repeat(window - 1 - i);
        final int offset = i;
        assertEquals(head + escape + tail, JIUtil.escapeJson(head + c + tail),
            () -> "offset " + offset + " of 0x" + Integer.toHexString(c));
      }
    }
  }

  @Test
  void testEscapeJsonHandlesTwoEscapesAtEveryOffsetPair() {
    // The span copy runs *between* two escapes, so it is only exercised by a
    // second escape. Walking both offsets over a window covers adjacent escapes
    // (zero-length span), escapes at the very start and end, and every gap in
    // between — the arrangements the unguarded getChars calls have to survive.
    final int window = 16;
    for (final char first : new char[]{'"', '\\', (char) 0x01}) {
      for (final char second : new char[]{'\n', (char) 0x1F}) {
        final var firstEscape = JIUtil.escapeJson(String.valueOf(first));
        final var secondEscape = JIUtil.escapeJson(String.valueOf(second));
        for (int i = 0; i < window; ++i) {
          for (int j = i + 1; j < window; ++j) {
            final var raw = "b".repeat(i) + first + "b".repeat(j - i - 1) + second
                + "b".repeat(window - j - 1);
            final var want = "b".repeat(i) + firstEscape + "b".repeat(j - i - 1) + secondEscape
                + "b".repeat(window - j - 1);
            final int a = i;
            final int b = j;
            assertEquals(want, JIUtil.escapeJson(raw), () -> "escapes at " + a + " and " + b);
          }
        }
      }
    }
  }

  @Test
  void testEscapeJsonPassesNonAsciiThroughUntouched() {
    // Only 0x00-0x1F, '"' and '\\' are escaped; everything above stays raw, so
    // non-ASCII costs no allocation at all
    for (final var raw : new String[]{"aéb", "a中b", "a😀b", "ab", "é中"}) {
      assertSame(raw, JIUtil.escapeJson(raw), () -> "should pass through: " + raw);
    }

    // A surrogate pair is two chars to this method and neither is in the escape
    // set, so the pair survives intact through an embed-and-parse round trip
    final var emoji = "a😀b";
    final var ji = JsonIterator.parse("{\"v\":\"" + JIUtil.escapeJson(emoji) + "\"}");
    ji.skipUntil("v");
    assertEquals(emoji, ji.readString());

    // Lone surrogates are passed through verbatim: this method escapes, it does
    // not validate UTF-16 pairing, and whether an unpaired surrogate survives is
    // decided by however the caller encodes the result (a UTF-8 encode replaces
    // it). Pinned so a change here is a deliberate one
    assertSame("a\uD83Db", JIUtil.escapeJson("a\uD83Db"));
    assertSame("a\uDE00b", JIUtil.escapeJson("a\uDE00b"));
  }

  @Test
  void testEscapeJsonAllocatesOnlyWhenEscaping() {
    // the same-instance contract is half the story; the complement is that a
    // string needing an escape must come back as a different instance
    assertSame("clean", JIUtil.escapeJson("clean"));
    assertNotSame("a\"b", JIUtil.escapeJson("a\"b"));
    assertNotSame("a\\b", JIUtil.escapeJson("a\\b"));
    assertNotSame("a\nb", JIUtil.escapeJson("a\nb"));
  }

  @Test
  void testEscapeJsonTreatsItsOwnOutputAsRawText() {
    // escapeJson is not idempotent, by design: it escapes raw text rather than
    // preserving existing escape sequences, so escaping twice doubles them.
    // Callers escape once
    final var once = JIUtil.escapeJson("a\"b");
    assertEquals("a\\\"b", once);
    assertEquals("a\\\\\\\"b", JIUtil.escapeJson(once));
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
  void testEscapeQuotesCheckedBackslashRunBeforeQuote() {
    // The checked scan counts the whole backslash run, not just the adjacent
    // char: an even run means the quote itself is unescaped and must gain a
    // backslash, an odd run means it is already escaped and the input comes
    // back as the same instance.
    assertEquals("a\\\\\\\"", JIUtil.escapeQuotesChecked("a\\\\\""));
    final var oddRun = "a\\\\\\\"";
    assertSame(oddRun, JIUtil.escapeQuotesChecked(oddRun));
    // A run reaching index 0 exercises the count's lower bound.
    assertEquals("\\\\\\\"", JIUtil.escapeQuotesChecked("\\\\\""));
    final var oddRunAtStart = "\\\\\\\"";
    assertSame(oddRunAtStart, JIUtil.escapeQuotesChecked(oddRunAtStart));
  }

  @Test
  void testEscapeQuotesRemoveNewLinesCheckedBackslashRunBeforeQuote() {
    // Same run-parity contract as escapeQuotesChecked, through the variant
    // with its own copy of the counting scan.
    assertEquals("a\\\\\\\"", JIUtil.escapeQuotesRemoveNewLinesChecked("a\\\\\""));
    final var oddRun = "a\\\\\\\"";
    assertSame(oddRun, JIUtil.escapeQuotesRemoveNewLinesChecked(oddRun));
    assertEquals("\\\\\\\"", JIUtil.escapeQuotesRemoveNewLinesChecked("\\\\\""));
    final var oddRunAtStart = "\\\\\\\"";
    assertSame(oddRunAtStart, JIUtil.escapeQuotesRemoveNewLinesChecked(oddRunAtStart));
    // A single escaped quote scans past; a later newline still routes to the
    // strip with everything before it preserved verbatim.
    final var escapedOnly = "a\\\"";
    assertSame(escapedOnly, JIUtil.escapeQuotesRemoveNewLinesChecked(escapedOnly));
    assertEquals("a\\\"b", JIUtil.escapeQuotesRemoveNewLinesChecked("a\\\"b\n"));
  }

  @Test
  void testEscapeQuotesRemoveNewLinesLeadingNewlineOnly() {
    // A leading newline stripped with nothing escaped is the one shape where
    // the scan ends having emitted nothing yet must NOT return the input
    // unchanged.
    assertEquals("abc", JIUtil.escapeQuotesRemoveNewLines("\nabc"));
    assertEquals("abc", JIUtil.escapeQuotesRemoveNewLines("\rabc"));
    assertEquals("abc", JIUtil.escapeQuotesRemoveNewLinesChecked("\nabc"));
    assertEquals("", JIUtil.escapeQuotesRemoveNewLines("\n\r"));
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
