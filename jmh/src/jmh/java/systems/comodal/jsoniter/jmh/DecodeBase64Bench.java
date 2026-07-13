package systems.comodal.jsoniter.jmh;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import systems.comodal.jsoniter.CharBufferFunction;
import systems.comodal.jsoniter.JsonIterator;

import java.util.Base64;
import java.util.concurrent.TimeUnit;

/// Referee for the char-source DECODE_BASE64 revert: the scalar narrowing
/// loop versus the deleted two-ShortVector narrowing, both applied through
/// the real chars-iterator path (applyChars on string values). Only the
/// chars-family iterators can ever reach this code — the bytes family
/// overrides decodeBase64String with a zero-copy span decode — so this
/// measures what that revert would cost a hypothetical char[] consumer.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class DecodeBase64Bench {

  /// Decoded payload sizes: a 64-byte signature-ish blob, a 1 KiB account,
  /// and a 16 KiB account — base64 lengths 88, 1368, and 21848 chars.
  @Param({"64", "1024", "16384"})
  private int payloadBytes;

  private static final int VALUES = 64;

  private char[] doc;
  private JsonIterator charsIterator;

  @Setup
  public void setup() {
    final var payload = new byte[payloadBytes];
    for (int i = 0; i < payload.length; ++i) {
      payload[i] = (byte) (i * 31);
    }
    final var b64 = Base64.getEncoder().encodeToString(payload);
    final var sb = new StringBuilder(VALUES * (b64.length() + 3)).append('[');
    for (int i = 0; i < VALUES; ++i) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append('"').append(b64).append('"');
    }
    doc = sb.append(']').toString().toCharArray();
    charsIterator = JsonIterator.parse(doc);

    if (decode(Base64Narrow.SCALAR) != decode(Base64Narrow.VECTOR)) {
      throw new IllegalStateException("variants disagree");
    }
  }

  private long decode(final CharBufferFunction<byte[]> narrow) {
    final var ji = charsIterator.reset(doc);
    long sum = 0;
    while (ji.readArray()) {
      final byte[] decoded = ji.applyChars(narrow);
      sum += decoded.length + decoded[decoded.length - 1];
    }
    return sum;
  }

  @Benchmark
  public long scalarNarrow() {
    return decode(Base64Narrow.SCALAR);
  }

  @Benchmark
  public long vectorNarrow() {
    return decode(Base64Narrow.VECTOR);
  }
}

/// Holds the vector-typed constants outside the @State class: the JMH
/// bytecode generator reflects benchmark-class fields in a JVM without the
/// incubator module, so vector types must not appear as bench fields.
final class Base64Narrow {

  private static final VectorSpecies<Byte> BYTE_SPECIES = ByteVector.SPECIES_PREFERRED.length() >= 16
      ? ByteVector.SPECIES_PREFERRED
      : ByteVector.SPECIES_128;
  private static final VectorSpecies<Short> SHORT_SPECIES =
      VectorSpecies.of(short.class, BYTE_SPECIES.vectorShape());

  static final CharBufferFunction<byte[]> SCALAR = (chars, offset, len) -> {
    final byte[] ascii = new byte[len];
    for (int i = 0; i < len; ++i) {
      ascii[i] = (byte) chars[offset + i];
    }
    return Base64.getDecoder().decode(ascii);
  };

  static final CharBufferFunction<byte[]> VECTOR = (chars, offset, len) -> {
    final byte[] ascii = new byte[len];
    final int byteLanes = BYTE_SPECIES.length();
    final int shortLanes = SHORT_SPECIES.length();
    int i = 0;
    for (; i + byteLanes <= len; i += byteLanes) {
      final var low = ShortVector.fromCharArray(SHORT_SPECIES, chars, offset + i);
      final var high = ShortVector.fromCharArray(SHORT_SPECIES, chars, offset + i + shortLanes);
      ((ByteVector) low.convertShape(VectorOperators.S2B, BYTE_SPECIES, 0))
          .or(high.convertShape(VectorOperators.S2B, BYTE_SPECIES, -1))
          .intoArray(ascii, i);
    }
    for (; i < len; ++i) {
      ascii[i] = (byte) chars[offset + i];
    }
    return Base64.getDecoder().decode(ascii);
  };

  private Base64Narrow() {
  }
}
