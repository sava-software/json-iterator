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

  private static int sequenceLength(final int lead) {
    if (lead >= 0xF0 && lead <= 0xF7) {
      return 4;
    }
    if (lead >= 0xE0 && lead <= 0xEF) {
      return 3;
    }
    return 2;
  }

  /// A sequence's *shape* is a legal lead followed by continuation bytes. That
  /// is weaker than validity — an overlong is well-shaped — but it is what
  /// decides where the sequence ends, and therefore where the cursor lands.
  private static boolean wellShaped(final int[] seq) {
    if (seq[0] < 0xC0 || seq[0] > 0xF7) {
      return false;
    }
    for (int i = 1; i < seq.length; ++i) {
      if ((seq[i] & 0xC0) != 0x80) {
        return false;
      }
    }
    return true;
  }

  /// Holds `readString` to the JDK's strict decoder — an oracle independent of
  /// this implementation — over every candidate lead and every byte value at
  /// each continuation position. The 2-byte sweep is the full C0-DF x 00-FF
  /// product; longer forms vary each continuation independently, which reaches
  /// the third and fourth checks a second-byte-only sweep leaves untested.
  /// Accepted inputs must also decode to the same text and leave both read and
  /// skip cursors immediately after the closing quote.
  ///
  /// `skip()` is held to the weaker contract it actually owes: it decodes
  /// nothing, so it may walk past an overlong, but a malformed *shape* moves
  /// the end of the sequence and must reject.
  @Test
  void test_utf8_acceptance_matches_the_jdk_decoder() {
    final var decoder = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);

    for (int lead = 0xC0; lead <= 0xF7; ++lead) {
      final int len = sequenceLength(lead);
      for (int position = 1; position < len; ++position) {
        for (int variant = 0; variant <= 0xFF; ++variant) {
          final int[] seq = new int[len];
          seq[0] = lead;
          for (int i = 1; i < len; ++i) {
            seq[i] = 0xBF;
          }
          seq[position] = variant;
          assertDecodesLikeTheJdk(decoder, seq);
        }
      }
    }

    // Continuation bytes cannot lead a sequence, and F8-FF have no UTF-8 form.
    for (int lead = 0x80; lead <= 0xBF; ++lead) {
      assertDecodesLikeTheJdk(decoder, new int[]{lead});
    }
    for (int lead = 0xF8; lead <= 0xFF; ++lead) {
      assertDecodesLikeTheJdk(decoder, new int[]{lead, 0xBF, 0xBF, 0xBF});
    }

    // The code-point boundaries each length owns, in both their shortest form
    // and the longer spelling of the same value.
    for (final int[] seq : new int[][]{
        {0xC2, 0x80},                    // U+0080, first 2-byte
        {0xDF, 0xBF},                    // U+07FF, last 2-byte
        {0xE0, 0xA0, 0x80},              // U+0800, first 3-byte
        {0xE0, 0x9F, 0xBF},              // U+07FF spelled in 3 bytes: overlong
        {0xED, 0x9F, 0xBF},              // U+D7FF, last before the surrogates
        {0xEE, 0x80, 0x80},              // U+E000, first after them
        {0xEF, 0xBF, 0xBF},              // U+FFFF, last 3-byte
        {0xF0, 0x90, 0x80, 0x80},        // U+10000, first 4-byte
        {0xF0, 0x8F, 0xBF, 0xBF},        // U+FFFF spelled in 4 bytes: overlong
        {0xF4, 0x8F, 0xBF, 0xBF},        // U+10FFFF, last code point
        {0xF4, 0x90, 0x80, 0x80}}) {     // U+110000, one past
      assertDecodesLikeTheJdk(decoder, seq);
    }
  }

  private static void assertDecodesLikeTheJdk(final java.nio.charset.CharsetDecoder decoder, final int[] seq) {
    final byte[] doc = quoted(seq);
    final var label = java.util.Arrays.toString(seq);

    final String expected;
    try {
      expected = decoder.reset().decode(java.nio.ByteBuffer.wrap(doc, 1, doc.length - 2)).toString();
    } catch (final java.nio.charset.CharacterCodingException malformed) {
      assertThrows(JsonException.class, () -> JsonIterator.parse(doc).readString(),
          () -> "readString accepted JDK-invalid UTF-8: " + label);
      if (!wellShaped(seq)) {
        assertThrows(JsonException.class, () -> JsonIterator.parse(doc).skip(),
            () -> "skip accepted a malformed sequence shape: " + label);
      }
      return;
    }

    final var reader = JsonIterator.parse(doc);
    assertEquals(expected, reader.readString(), () -> "readString decoded differently from the JDK: " + label);
    assertEquals(doc.length, reader.mark(), () -> "readString misplaced the cursor: " + label);

    final var skipper = JsonIterator.parse(doc);
    skipper.skip();
    assertEquals(doc.length, skipper.mark(), () -> "skip misplaced the cursor: " + label);
  }

  /// The sharp end of an unvalidated continuation byte: it is not a wrong
  /// character, it is a wrong *document*. A lead byte immediately before the
  /// closing quote used to absorb the quote, and the scan then re-synchronised
  /// on a later one — turning three array elements into one, with no error.
  @Test
  void test_lead_byte_cannot_swallow_the_closing_quote() {
    for (final int lead : new int[]{0xC2, 0xE1, 0xF0}) {
      // "<lead>" — the closing quote is the byte a continuation was expected at
      final byte[] doc = quoted(lead);
      assertThrows(JsonException.class, () -> JsonIterator.parse(doc).readString(), "lead=" + lead);
      assertThrows(JsonException.class, () -> JsonIterator.parse(doc).skip(), "skip lead=" + lead);

      // and in a document with more to consume after it, where the old scan
      // ran on into the following structure instead of stopping
      final var array = new byte[]{'[', '"', (byte) lead, '"', ',', '"', ']', '"', ',', '"', 'z', '"', ']'};
      assertThrows(JsonException.class,
          () -> JsonIterator.parse(array).readList(JsonIterator::readString), "array lead=" + lead);
      assertThrows(JsonException.class,
          () -> JsonIterator.parse(array).skip(), "skip array lead=" + lead);
    }

    // the same documents with an ascii byte in place of the lead parse normally
    final var clean = new byte[]{'[', '"', 'x', '"', ',', '"', ']', '"', ',', '"', 'z', '"', ']'};
    assertEquals(java.util.List.of("x", "]", "z"), JsonIterator.parse(clean).readList(JsonIterator::readString));
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
  void test_supplementary_split_threshold() {
    // The property this is really about: U+FFFF is the last code point below
    // the surrogate-split threshold and U+10000 the first above it, so one
    // decodes as a single char and the other as a pair. Spelled in the
    // shortest form, which is the only legal one.
    assertEquals("￿", JsonIterator.parse(quoted(0xEF, 0xBF, 0xBF)).readString());
    JsonIterator.parse(quoted(0xEF, 0xBF, 0xBF)).skip();
    assertEquals("𐀀", JsonIterator.parse(quoted(0xF0, 0x90, 0x80, 0x80)).readString());
    JsonIterator.parse(quoted(0xF0, 0x90, 0x80, 0x80)).skip();

    // This test used to assert that F0 8F BF BF — the 4-byte spelling of
    // U+FFFF — decoded to that char, which read as a design statement but was
    // an overlong encoding pinned as intended behaviour. RFC 3629 admits only
    // the shortest form and the JDK's decoder rejects this one.
    final byte[] overlong = quoted(0xF0, 0x8F, 0xBF, 0xBF);
    assertThrows(JsonException.class, () -> JsonIterator.parse(overlong).readString());

    // skip() still walks past it: it validates the sequence's *shape*, which is
    // what keeps the cursor in the right place, and decodes nothing, so an
    // overlong it steps over reaches no caller.
    JsonIterator.parse(overlong).skip();
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

  /// The `char[]` a [FieldBufferPredicate] receives is the iterator's reusable
  /// decode buffer, and widening replaces it only when a name outgrows it — so
  /// two names that already fit must arrive in the *same* array. Forcing the
  /// grow branch is content-identical (every name still decodes), so no
  /// `assertEquals` can see it; buffer identity can. This is the capability-free
  /// half of the widening contract: [TestAllocation] asserts the same property
  /// with byte counts, but that whole class is skipped on a JVM without thread
  /// allocation counters.
  @Test
  void test_field_buffer_is_reused_when_the_name_already_fits() {
    final byte[] doc = "{\"alpha\":1,\"bravo\":2}".getBytes();
    final char[][] seen = new char[2][];
    final int[] n = {0};
    JsonIterator.parse(doc, 32).testObject((buf, _, len, ji) -> {
      assertEquals(5, len);
      seen[n[0]++] = buf;
      ji.skip();
      return true;
    });
    assertEquals(2, n[0]);
    assertSame(seen[0], seen[1], "the reusable field buffer was reallocated for a name that already fit");
  }
}
