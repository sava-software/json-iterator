package systems.comodal.jsoniter.jmh;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import systems.comodal.jsoniter.IndexedJsonIterator;
import systems.comodal.jsoniter.JsonIterator;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/// Workloads that target the vectorized paths not exercised by twitter.json:
/// - `skipHeavy`: skip a very large container to reach a trailing field.
/// - `longStrings`: long string values via readString and via applyChars
///   (the char[] IOC path used by testObject/skipUntil field matching).
/// - `bigLongs`: arrays of 18-19 digit longs (SWAR eight-digit parsing).
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class SyntheticBench {

  private byte[] skipDoc;
  private byte[] stringsDoc;
  private byte[] longsDoc;
  private byte[] doublesDoc;
  private JsonIterator jsonIterator;
  private IndexedJsonIterator jsonIter;

  @Setup
  public void setup() {
    final var skip = new StringBuilder(1 << 20).append("{\"skip\":[");
    for (int i = 0; i < 2_000; ++i) {
      if (i > 0) {
        skip.append(',');
      }
      skip.append("{\"id\":").append(i)
          .append(",\"text\":\"some value with brackets ] } and [ { inside ").append(i).append("\"")
          .append(",\"nested\":[1,2,[3,4]]}");
    }
    skipDoc = skip.append("],\"want\":42}").toString().getBytes(StandardCharsets.UTF_8);

    final var strings = new StringBuilder(1 << 20).append('[');
    for (int i = 0; i < 500; ++i) {
      if (i > 0) {
        strings.append(',');
      }
      strings.append('"').append("a moderately long ascii string value 0123456789 ".repeat(4)).append(i).append('"');
    }
    stringsDoc = strings.append(']').toString().getBytes(StandardCharsets.UTF_8);

    final var longs = new StringBuilder(1 << 16).append('[');
    for (int i = 0; i < 2_000; ++i) {
      if (i > 0) {
        longs.append(',');
      }
      longs.append(1_234_567_890_123_456_789L - i);
    }
    longsDoc = longs.append(']').toString().getBytes(StandardCharsets.UTF_8);

    final var doubles = new StringBuilder(1 << 16).append('[');
    for (int i = 0; i < 2_000; ++i) {
      if (i > 0) {
        doubles.append(',');
      }
      doubles.append(i).append('.').append(1 + (i % 997)).append("e-").append(i % 31);
    }
    doublesDoc = doubles.append(']').toString().getBytes(StandardCharsets.UTF_8);

    jsonIterator = JsonIterator.parse(skipDoc);
    jsonIter = IndexedJsonIterator.parse(skipDoc);

    check(skipHeavy_jsonIterator(), skipHeavy_jsonIter());
    check(longStrings_read_jsonIterator(), longStrings_read_jsonIter());
    check(longStrings_apply_jsonIterator(), longStrings_read_jsonIter());
    check(bigLongs_jsonIterator(), bigLongs_jsonIter());
    check(Double.doubleToLongBits(doubles_jsonIterator()), Double.doubleToLongBits(doubles_indexed()));
  }

  private static void check(final long a, final long b) {
    if (a != b) {
      throw new IllegalStateException(a + " != " + b);
    }
  }

  @Benchmark
  public long skipHeavy_jsonIterator() {
    final var ji = jsonIterator.reset(skipDoc);
    return ji.skipUntil("want").readLong();
  }

  @Benchmark
  public long skipHeavy_jsonIter() {
    final var ji = jsonIter.reset(skipDoc);
    return ji.skipUntil("want").readLong();
  }

  @Benchmark
  public long longStrings_read_jsonIterator() {
    final var ji = jsonIterator.reset(stringsDoc);
    long sum = 0;
    while (ji.readArray()) {
      sum += ji.readString().length();
    }
    return sum;
  }

  @Benchmark
  public long longStrings_apply_jsonIterator() {
    final var ji = jsonIterator.reset(stringsDoc);
    long sum = 0;
    while (ji.readArray()) {
      sum += ji.applyCharsAsInt((buf, offset, len) -> len);
    }
    return sum;
  }

  @Benchmark
  public long longStrings_read_jsonIter() {
    final var ji = jsonIter.reset(stringsDoc);
    long sum = 0;
    while (ji.readArray()) {
      sum += ji.readString().length();
    }
    return sum;
  }

  @Benchmark
  public long bigLongs_jsonIterator() {
    final var ji = jsonIterator.reset(longsDoc);
    long sum = 0;
    while (ji.readArray()) {
      sum += ji.readLong();
    }
    return sum;
  }

  @Benchmark
  public long bigLongs_jsonIter() {
    final var ji = jsonIter.reset(longsDoc);
    long sum = 0;
    while (ji.readArray()) {
      sum += ji.readLong();
    }
    return sum;
  }

  @Benchmark
  public double doubles_jsonIterator() {
    final var ji = jsonIterator.reset(doublesDoc);
    double sum = 0;
    while (ji.readArray()) {
      sum += ji.readDouble();
    }
    return sum;
  }

  @Benchmark
  public double doubles_indexed() {
    final var ji = jsonIter.reset(doublesDoc);
    double sum = 0;
    while (ji.readArray()) {
      sum += ji.readDouble();
    }
    return sum;
  }
}
