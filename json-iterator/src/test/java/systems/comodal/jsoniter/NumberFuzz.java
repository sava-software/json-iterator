package systems.comodal.jsoniter;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

/// Jazzer entry point exercising the integer readers, which take a different
/// path than the double parser (digit-at-a-time accumulation over
/// `peekIntDigitChar` instead of the token scan):
///
/// - `readLong`/`readInt` must agree between the byte and char sourced
///   iterators, and when the raw token is a pure integer the JDK parse of that
///   token must match — including agreeing on overflow rejection.
/// - `readBigDecimal` and `readBigInteger` must agree between the two sources.
///
/// Rejections must be [JsonException] on either path. Acceptance equivalence
/// between the two sources is only enforced for pure-ASCII inputs, where the
/// sources see identical code points.
///
/// Deliberately has no Jazzer imports so it compiles with the regular test sources;
/// the raw `byte[]` signature is all the driver needs.
///
/// Run with `./gradlew :json-iterator:fuzzNumber [-PmaxFuzzTime=<seconds>]`.
public final class NumberFuzz {

  public static void fuzzerTestOneInput(final byte[] data) {
    final char[] chars = new char[data.length];
    for (int i = 0; i < data.length; i++) {
      chars[i] = (char) (data[i] & 0xff);
    }
    final boolean ascii = allAscii(data);

    Long bytesLong = null;
    try {
      bytesLong = JsonIterator.parse(data).readLong();
    } catch (final JsonException expected) {
    }
    Long charsLong = null;
    try {
      charsLong = JsonIterator.parse(chars).readLong();
    } catch (final JsonException expected) {
    }
    checkAgreement("readLong", bytesLong, charsLong, ascii);

    Integer bytesInt = null;
    try {
      bytesInt = JsonIterator.parse(data).readInt();
    } catch (final JsonException expected) {
    }
    Integer charsInt = null;
    try {
      charsInt = JsonIterator.parse(chars).readInt();
    } catch (final JsonException expected) {
    }
    checkAgreement("readInt", bytesInt, charsInt, ascii);

    // JDK oracle over the raw token, applicable when the whole token is a pure
    // integer (readLong legitimately stops at '.', 'e', etc.)
    if (bytesLong != null) {
      final String token = numberToken(data);
      if (token != null && isPureInteger(token)) {
        final long reference;
        try {
          reference = Long.parseLong(token);
        } catch (final NumberFormatException overflow) {
          throw new IllegalStateException(
              "readLong accepted an overflowing token '" + token + "' as " + bytesLong);
        }
        if (reference != bytesLong) {
          throw new IllegalStateException(String.format(
              "readLong disagrees with Long.parseLong for '%s': %s vs %s", token, bytesLong, reference));
        }
      }
    }
    if (bytesInt != null) {
      final String token = numberToken(data);
      if (token != null && isPureInteger(token)) {
        final int reference;
        try {
          reference = Integer.parseInt(token);
        } catch (final NumberFormatException overflow) {
          throw new IllegalStateException(
              "readInt accepted an overflowing token '" + token + "' as " + bytesInt);
        }
        if (reference != bytesInt) {
          throw new IllegalStateException(String.format(
              "readInt disagrees with Integer.parseInt for '%s': %s vs %s", token, bytesInt, reference));
        }
      }
    }

    BigDecimal bytesBigDecimal = null;
    try {
      bytesBigDecimal = JsonIterator.parse(data).readBigDecimal();
    } catch (final JsonException | NumberFormatException expected) {
    }
    BigDecimal charsBigDecimal = null;
    try {
      charsBigDecimal = JsonIterator.parse(chars).readBigDecimal();
    } catch (final JsonException | NumberFormatException expected) {
    }
    checkAgreement("readBigDecimal", bytesBigDecimal, charsBigDecimal, ascii);

    BigInteger bytesBigInteger = null;
    try {
      bytesBigInteger = JsonIterator.parse(data).readBigInteger();
    } catch (final JsonException | NumberFormatException expected) {
    }
    BigInteger charsBigInteger = null;
    try {
      charsBigInteger = JsonIterator.parse(chars).readBigInteger();
    } catch (final JsonException | NumberFormatException expected) {
    }
    checkAgreement("readBigInteger", bytesBigInteger, charsBigInteger, ascii);
  }

  /// null when either reader rejected; both-null means both readers rejected,
  /// which checkAgreement accepts as agreement.
  private static void checkAgreement(final String op, final Object bytes, final Object chars, final boolean ascii) {
    if (bytes != null && chars != null) {
      if (!Objects.equals(bytes, chars)) {
        throw new IllegalStateException(op + " sources disagree: bytes " + bytes + " vs chars " + chars);
      }
    } else if (ascii && (bytes != null || chars != null)) {
      throw new IllegalStateException(op + " sources disagree on rejecting an ASCII input: bytes "
          + (bytes == null ? "rejected" : bytes) + ", chars " + (chars == null ? "rejected" : chars));
    }
  }

  /// The raw unquoted token, or null when the value is not a bare number.
  private static String numberToken(final byte[] data) {
    try {
      final var ji = JsonIterator.parse(data);
      return ji.whatIsNext() == ValueType.NUMBER ? ji.readNumberAsString() : null;
    } catch (final JsonException e) {
      return null;
    }
  }

  private static boolean isPureInteger(final String token) {
    final int from = token.startsWith("-") ? 1 : 0;
    if (from == token.length()) {
      return false;
    }
    for (int i = from; i < token.length(); i++) {
      final char c = token.charAt(i);
      if (c < '0' || c > '9') {
        return false;
      }
    }
    // a leading zero is JSON-invalid and the readers stop scanning at it,
    // legitimately reading a prefix of the token
    return token.charAt(from) != '0' || token.length() - from == 1;
  }

  private static boolean allAscii(final byte[] data) {
    for (final byte b : data) {
      if (b < 0) {
        return false;
      }
    }
    return true;
  }
}
