package systems.comodal.jsoniter.jmh;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import systems.comodal.jsoniter.JsonIterator;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/// Isolates the decodeBase64String change: the old implementations copied the
/// base64 content out of the buffer first (Arrays.copyOfRange on the byte
/// path, an intermediate String on the char path); the new ones decode
/// directly from the buffer (ByteBuffer.wrap) or via a plain char->byte
/// narrowing loop.
///
/// The `decoder_*` benchmarks compare the raw strategies on the same buffer
/// slice; the `iterator_*` benchmarks measure the current implementations
/// end-to-end, including field navigation.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class Base64Bench {

  @Param({"64", "4096", "262144"})
  int size;

  private byte[] data;
  private byte[] json;
  private char[] jsonChars;
  private int contentFrom;
  private int contentTo;
  private JsonIterator bytesIter;
  private JsonIterator charsIter;
  private jsoniter.v21.JsonIterator bytesIter21;
  private jsoniter.v21.JsonIterator charsIter21;

  @Setup
  public void setup() {
    data = new byte[size];
    new Random(size).nextBytes(data);
    final var base64 = Base64.getEncoder().encodeToString(data);
    final var doc = "{\"data\":\"" + base64 + "\"}";
    json = doc.getBytes(StandardCharsets.US_ASCII);
    jsonChars = doc.toCharArray();
    contentFrom = "{\"data\":\"".length();
    contentTo = contentFrom + base64.length();
    bytesIter = JsonIterator.parse(json);
    charsIter = JsonIterator.parse(jsonChars);
    bytesIter21 = jsoniter.v21.JsonIterator.parse(json);
    charsIter21 = jsoniter.v21.JsonIterator.parse(jsonChars);

    check(decoder_copyOfRange_old());
    check(decoder_byteBuffer_new());
    check(decoder_viaString_old());
    check(iterator_bytes());
    check(iterator_chars());
    check(iterator_bytes_jsonIterator21());
    check(iterator_chars_jsonIterator21());
  }

  private void check(final byte[] decoded) {
    if (!Arrays.equals(data, decoded)) {
      throw new IllegalStateException("decoders disagree");
    }
  }

  // Raw decode strategies over the same buffer slice.

  /// The previous byte-path implementation: copy the slice, then decode.
  @Benchmark
  public byte[] decoder_copyOfRange_old() {
    return Base64.getDecoder().decode(Arrays.copyOfRange(json, contentFrom, contentTo));
  }

  /// The new byte-path implementation: decode directly from the buffer.
  @Benchmark
  public byte[] decoder_byteBuffer_new() {
    final var decoded = Base64.getDecoder().decode(ByteBuffer.wrap(json, contentFrom, contentTo - contentFrom));
    final byte[] out = decoded.array();
    return decoded.limit() == out.length ? out : Arrays.copyOf(out, decoded.limit());
  }

  /// The previous char-path implementation: materialize a String, then decode
  /// (which internally copies the String to bytes a second time).
  @Benchmark
  public byte[] decoder_viaString_old() {
    return Base64.getDecoder().decode(new String(jsonChars, contentFrom, contentTo - contentFrom));
  }

  // The current implementations end-to-end, including field navigation.

  @Benchmark
  public byte[] iterator_bytes() {
    return bytesIter.reset(json).skipUntil("data").decodeBase64String();
  }

  @Benchmark
  public byte[] iterator_chars() {
    return charsIter.reset(jsonChars).skipUntil("data").decodeBase64String();
  }

  // The published 21.0.12 implementations end-to-end.

  @Benchmark
  public byte[] iterator_bytes_jsonIterator21() {
    return bytesIter21.reset(json).skipUntil("data").decodeBase64String();
  }

  @Benchmark
  public byte[] iterator_chars_jsonIterator21() {
    return charsIter21.reset(jsonChars).skipUntil("data").decodeBase64String();
  }
}
