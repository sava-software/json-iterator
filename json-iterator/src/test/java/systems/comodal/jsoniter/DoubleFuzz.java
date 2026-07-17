package systems.comodal.jsoniter;

import static java.lang.Double.doubleToLongBits;
import static java.lang.Float.floatToIntBits;

/// Jazzer entry point exercising the number parsers against the JDK reference:
/// whenever `readDouble` accepts an input, the accepted token must parse to the
/// bit-identical value through `Double.parseDouble`, the binary32-parameterized
/// `readFloat` must bit-match `Float.parseFloat`, and the char sourced iterator
/// must agree with the byte sourced one.
///
/// Rejections must be [JsonException] on either path. Acceptance equivalence
/// between the two sources is only enforced for pure-ASCII inputs, where the
/// sources see identical code points.
///
/// Deliberately has no Jazzer imports so it compiles with the regular test sources;
/// the raw `byte[]` signature is all the driver needs.
///
/// Run with `./gradlew :json-iterator:fuzzDouble [-PmaxFuzzTime=<seconds>]`.
public final class DoubleFuzz {

  public static void fuzzerTestOneInput(final byte[] data) {
    Double bytesValue = null;
    try {
      bytesValue = JsonIterator.parse(data).readDouble();
    } catch (final JsonException expected) {
    }

    Float bytesFloat = null;
    try {
      bytesFloat = JsonIterator.parse(data).readFloat();
    } catch (final JsonException expected) {
    }

    final char[] chars = new char[data.length];
    for (int i = 0; i < data.length; i++) {
      chars[i] = (char) (data[i] & 0xff);
    }
    Double charsValue = null;
    try {
      charsValue = JsonIterator.parse(chars).readDouble();
    } catch (final JsonException expected) {
    }

    if (bytesValue != null) {
      final var token = token(data);
      final double reference = Double.parseDouble(token);
      if (doubleToLongBits(reference) != doubleToLongBits(bytesValue)) {
        throw new IllegalStateException(String.format(
            "readDouble disagrees with Double.parseDouble for '%s': %s vs %s",
            token, bytesValue, reference));
      }
      if (bytesFloat == null) {
        throw new IllegalStateException("readFloat rejected a token readDouble accepted: " + token);
      }
      final float floatReference = Float.parseFloat(token);
      if (floatToIntBits(floatReference) != floatToIntBits(bytesFloat)) {
        throw new IllegalStateException(String.format(
            "readFloat disagrees with Float.parseFloat for '%s': %s vs %s",
            token, bytesFloat, floatReference));
      }
      if (charsValue != null && doubleToLongBits(charsValue) != doubleToLongBits(bytesValue)) {
        throw new IllegalStateException(String.format(
            "sources disagree for '%s': bytes %s vs chars %s", token, bytesValue, charsValue));
      }
    }

    if (allAscii(data) && (bytesValue != null) != (charsValue != null)) {
      throw new IllegalStateException("sources disagree on rejecting an ASCII input: bytes "
          + (bytesValue == null ? "rejected" : bytesValue)
          + ", chars " + (charsValue == null ? "rejected" : charsValue));
    }
  }

  /// Re-reads the raw token readDouble consumed; readDouble accepts a bare number
  /// or a quoted one, so mirror both.
  private static String token(final byte[] data) {
    final var ji = JsonIterator.parse(data);
    final var valueType = ji.whatIsNext();
    return switch (valueType) {
      case NUMBER -> ji.readNumberAsString();
      case STRING -> ji.readString();
      default -> throw new IllegalStateException("readDouble accepted a " + valueType);
    };
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
