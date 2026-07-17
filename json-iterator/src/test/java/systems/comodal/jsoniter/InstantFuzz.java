package systems.comodal.jsoniter;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Objects;

import static java.nio.charset.StandardCharsets.UTF_8;

/// Jazzer entry point exercising `readDateTime` — InstantParser consumes the
/// string's chars arithmetically, so this is a robustness net: any input must
/// either parse, or reject with [JsonException] or [DateTimeException] (the
/// documented contract); anything else — a bounds fault from a short or
/// malformed timestamp, an unchecked arithmetic error — is a finding. Both
/// sources must agree on the parsed instant.
///
/// Deliberately has no Jazzer imports so it compiles with the regular test sources;
/// the raw `byte[]` signature is all the driver needs.
///
/// Run with `./gradlew :json-iterator:fuzzInstant [-PmaxFuzzTime=<seconds>]`.
public final class InstantFuzz {

  public static void fuzzerTestOneInput(final byte[] data) {
    Instant bytesValue = null;
    boolean bytesRejected = false;
    try {
      bytesValue = JsonIterator.parse(data).readDateTime();
    } catch (final JsonException | DateTimeException expected) {
      bytesRejected = true;
    }

    final char[] chars = decodeStrict(data);
    if (chars == null) {
      return;
    }
    Instant charsValue = null;
    boolean charsRejected = false;
    try {
      charsValue = JsonIterator.parse(chars).readDateTime();
    } catch (final JsonException | DateTimeException expected) {
      charsRejected = true;
    }

    if (bytesRejected != charsRejected) {
      throw new IllegalStateException("sources disagree on rejection: bytes "
          + (bytesRejected ? "rejected" : bytesValue) + ", chars "
          + (charsRejected ? "rejected" : charsValue));
    }
    if (!bytesRejected && !Objects.equals(bytesValue, charsValue)) {
      throw new IllegalStateException("sources disagree: bytes " + bytesValue + " vs chars " + charsValue);
    }
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
}
