package systems.comodal.jsoniter;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;

/// Jazzer entry point exercising the byte and char sourced iterators differentially:
/// the same document walked through `BytesJsonIterator` (the raw UTF-8 bytes) and
/// `CharsJsonIterator` (the strict UTF-8 decoding of those bytes) must produce the
/// same event stream, or both must reject the document.
///
/// [JsonException] is the only accepted rejection on either path — any other
/// throwable is a finding.
///
/// Inputs that are not valid UTF-8 still run the bytes path as a
/// parses-or-rejects robustness check; only the comparison is skipped.
///
/// Deliberately has no Jazzer imports so it compiles with the regular test sources;
/// the raw `byte[]` signature is all the driver needs.
///
/// Run with `./gradlew :json-iterator:fuzzJson [-PmaxFuzzTime=<seconds>]`.
public final class JsonFuzz {

  /// Values nested deeper than this are skipped, not walked: the walker recurses per
  /// level and must not overflow the harness stack. skip() itself is iterative, so
  /// the library still scans the whole subtree.
  private static final int MAX_DEPTH = 64;

  /// Every string value is additionally resolved through [JsonIterator#matchString]
  /// and [JsonIterator#matchStringOrThrow] against a linear-scan oracle over these
  /// names. Chosen to stress the length + first/last-eight-bytes hash: word-load
  /// boundaries at eight and sixteen bytes, a pair differing only in the middle,
  /// and multibyte names forcing the char-source UTF-8 fallback.
  private static final List<String> MATCH_NAMES = List.of(
      "", "a", "ab", "abcdefg", "abcdefgh", "abcdefghi",
      "abcdefghijklmnop", "abcdefghijklmnopq",
      "prefix--MIDDLE--suffix", "prefix--CENTER--suffix",
      "поле", "值");
  private static final FieldMatcher MATCHER = FieldMatcher.of(MATCH_NAMES.toArray(String[]::new));

  public static void fuzzerTestOneInput(final byte[] data) {
    final var byteEvents = new ArrayList<String>();
    boolean bytesRejected = false;
    try {
      walk(JsonIterator.parse(data), byteEvents, 0);
    } catch (final JsonException expected) {
      bytesRejected = true;
    }

    final char[] chars = decodeStrict(data);
    if (chars == null) {
      return;
    }
    final var charEvents = new ArrayList<String>();
    boolean charsRejected = false;
    try {
      walk(JsonIterator.parse(chars), charEvents, 0);
    } catch (final JsonException expected) {
      charsRejected = true;
    }

    if (bytesRejected != charsRejected) {
      throw new IllegalStateException("sources disagree on rejection: bytes "
          + (bytesRejected ? "rejected" : "accepted " + summarize(byteEvents))
          + ", chars "
          + (charsRejected ? "rejected" : "accepted " + summarize(charEvents)));
    }
    if (!bytesRejected && !byteEvents.equals(charEvents)) {
      throw new IllegalStateException("event streams diverge: bytes "
          + summarize(byteEvents) + " vs chars " + summarize(charEvents));
    }
  }

  private static void walk(final JsonIterator ji, final List<String> events, final int depth) {
    switch (ji.whatIsNext()) {
      case STRING -> {
        final int start = ji.mark();
        final var str = ji.readString();
        final int end = ji.mark();
        matchDifferentially(ji, str, start, end);
        events.add("str:" + str);
      }
      case NUMBER -> events.add("num:" + ji.readNumberAsString());
      case BOOLEAN -> events.add("bool:" + ji.readBoolean());
      case NULL -> {
        ji.skip();
        events.add("null");
      }
      case ARRAY -> {
        if (depth == MAX_DEPTH) {
          ji.skip();
          events.add("deep-array");
          return;
        }
        events.add("[");
        while (ji.readArray()) {
          walk(ji, events, depth + 1);
        }
        events.add("]");
      }
      case OBJECT -> {
        if (depth == MAX_DEPTH) {
          ji.skip();
          events.add("deep-object");
          return;
        }
        events.add("{");
        ji.testObject(events, (evts, buf, offset, len, j) -> {
          evts.add("field:" + new String(buf, offset, len));
          walk(j, evts, depth + 1);
          return true;
        });
        events.add("}");
      }
      case INVALID -> {
        ji.skip();
        throw new IllegalStateException("skip accepted an invalid leading token");
      }
    }
  }

  /// Re-reads the string at [start, end) through both matcher entry points and
  /// checks them against the oracle: the index of the decoded string among
  /// [#MATCH_NAMES], or -1. A [JsonException] here propagates as an ordinary
  /// rejection — the byte/char comparison in the caller still applies.
  private static void matchDifferentially(final JsonIterator ji, final String str, final int start, final int end) {
    final int expected = MATCH_NAMES.indexOf(str);
    ji.reset(start);
    final int matched = ji.matchString(MATCHER);
    if (matched != expected) {
      throw new IllegalStateException("matchString resolved \"" + str + "\" to " + matched + ", expected " + expected);
    }
    ji.reset(start);
    try {
      final int index = ji.matchStringOrThrow(MATCHER);
      if (index != expected) {
        throw new IllegalStateException("matchStringOrThrow resolved \"" + str + "\" to " + index + ", expected " + expected);
      }
    } catch (final JsonException e) {
      if (expected >= 0) {
        throw new IllegalStateException("matchStringOrThrow rejected declared name \"" + str + '"', e);
      }
    }
    ji.reset(end);
  }

  private static char[] decodeStrict(final byte[] data) {
    final var decoder = UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      final var decoded = decoder.decode(ByteBuffer.wrap(data));
      final char[] chars = new char[decoded.remaining()];
      decoded.get(chars);
      return chars;
    } catch (final CharacterCodingException e) {
      return null;
    }
  }

  private static String summarize(final List<String> events) {
    final var joined = String.join(", ", events);
    return joined.length() > 512
        ? "[" + joined.substring(0, 512) + "..."
        : "[" + joined + "]";
  }
}
