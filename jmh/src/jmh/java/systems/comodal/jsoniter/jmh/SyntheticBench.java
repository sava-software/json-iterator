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
  private byte[] rfcDatesDoc;
  private byte[] isoDatesDoc;
  private JsonIterator jsonIterator;
  private jsoniter.published.JsonIterator jsonIteratorPublished;
  private IndexedJsonIterator jsonIndexed;

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

    final var rfcDates = new StringBuilder(1 << 17).append('[');
    final var isoDates = new StringBuilder(1 << 17).append('[');
    for (int i = 0; i < 2_000; ++i) {
      if (i > 0) {
        rfcDates.append(',');
        isoDates.append(',');
      }
      rfcDates.append("\"Fri, 04 Oct 2019 16:2").append(i % 10).append(":36 GMT\"");
      isoDates.append("\"2019-10-04T16:2").append(i % 10).append(":36.123456789Z\"");
    }
    rfcDatesDoc = rfcDates.append(']').toString().getBytes(StandardCharsets.UTF_8);
    isoDatesDoc = isoDates.append(']').toString().getBytes(StandardCharsets.UTF_8);

    jsonIterator = JsonIterator.parse(skipDoc);
    jsonIteratorPublished = jsoniter.published.JsonIterator.parse(skipDoc);
    jsonIndexed = IndexedJsonIterator.parse(skipDoc);

    check(skipHeavy_jsonIterator(), skipHeavy_jsonIndexed());
    check(longStrings_read_jsonIterator(), longStrings_read_jsonIndexed());
    check(longStrings_apply_jsonIterator(), longStrings_read_jsonIndexed());
    check(bigLongs_jsonIterator(), bigLongs_jsonIndexed());
    check(Double.doubleToLongBits(doubles_jsonIterator()), Double.doubleToLongBits(doubles_jsonIndexed()));

    check(skipHeavy_jsonIteratorPublished(), skipHeavy_jsonIterator());
    check(longStrings_read_jsonIteratorPublished(), longStrings_read_jsonIterator());
    check(longStrings_apply_jsonIteratorPublished(), longStrings_apply_jsonIterator());
    check(bigLongs_jsonIteratorPublished(), bigLongs_jsonIterator());
    check(dates_rfc1123_jsonIteratorPublished(), dates_rfc1123());
    check(dates_iso_jsonIteratorPublished(), dates_iso());
    // 25.1.0 (published) shares the correctly-rounded Eisel-Lemire parser, so the sums
    // must agree bit for bit
    check(Double.doubleToLongBits(doubles_jsonIteratorPublished()), Double.doubleToLongBits(doubles_jsonIterator()));
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
  public long skipHeavy_jsonIteratorPublished() {
    final var ji = jsonIteratorPublished.reset(skipDoc);
    return ji.skipUntil("want").readLong();
  }

  @Benchmark
  public long skipHeavy_jsonIndexed() {
    final var ji = jsonIndexed.reset(skipDoc);
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
  public long longStrings_read_jsonIteratorPublished() {
    final var ji = jsonIteratorPublished.reset(stringsDoc);
    long sum = 0;
    while (ji.readArray()) {
      sum += ji.readString().length();
    }
    return sum;
  }

  @Benchmark
  public long longStrings_apply_jsonIteratorPublished() {
    final var ji = jsonIteratorPublished.reset(stringsDoc);
    long sum = 0;
    while (ji.readArray()) {
      sum += ji.applyCharsAsInt((buf, offset, len) -> len);
    }
    return sum;
  }

  @Benchmark
  public long longStrings_read_jsonIndexed() {
    final var ji = jsonIndexed.reset(stringsDoc);
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
  public long bigLongs_jsonIteratorPublished() {
    final var ji = jsonIteratorPublished.reset(longsDoc);
    long sum = 0;
    while (ji.readArray()) {
      sum += ji.readLong();
    }
    return sum;
  }

  @Benchmark
  public long bigLongs_jsonIndexed() {
    final var ji = jsonIndexed.reset(longsDoc);
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
  public double doubles_jsonIteratorPublished() {
    final var ji = jsonIteratorPublished.reset(doublesDoc);
    double sum = 0;
    while (ji.readArray()) {
      sum += ji.readDouble();
    }
    return sum;
  }

  @Benchmark
  public double doubles_jsonIndexed() {
    final var ji = jsonIndexed.reset(doublesDoc);
    double sum = 0;
    while (ji.readArray()) {
      sum += ji.readDouble();
    }
    return sum;
  }

  @Benchmark
  public long dates_rfc1123() {
    final var ji = jsonIterator.reset(rfcDatesDoc);
    long sum = 0;
    while (ji.readArray()) {
      sum += ji.readDateTime().getEpochSecond();
    }
    return sum;
  }

  @Benchmark
  public long dates_iso() {
    final var ji = jsonIterator.reset(isoDatesDoc);
    long sum = 0;
    while (ji.readArray()) {
      sum += ji.readDateTime().getEpochSecond();
    }
    return sum;
  }

  @Benchmark
  public long dates_rfc1123_jsonIteratorPublished() {
    final var ji = jsonIteratorPublished.reset(rfcDatesDoc);
    long sum = 0;
    while (ji.readArray()) {
      sum += ji.readDateTime().getEpochSecond();
    }
    return sum;
  }

  @Benchmark
  public long dates_iso_jsonIteratorPublished() {
    final var ji = jsonIteratorPublished.reset(isoDatesDoc);
    long sum = 0;
    while (ji.readArray()) {
      sum += ji.readDateTime().getEpochSecond();
    }
    return sum;
  }
}
