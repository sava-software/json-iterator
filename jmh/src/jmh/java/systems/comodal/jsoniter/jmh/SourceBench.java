package systems.comodal.jsoniter.jmh;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/// Prices the four data sources JsonIterator accepts, over one identical walk
/// (visit every object field name, skip every value). The walk is the constant;
/// each pairwise delta isolates one cost a consumer actually chooses:
///
/// - `bytes_reset` — the recommended shape: one iterator, `reset(byte[])` per
///   document. The baseline every other row is measured against.
/// - `bytes_parse` vs `bytes_reset` — the cost of allocating an iterator (and
///   its char buffer) per document instead of reusing one.
/// - `string_parse` vs `bytes_parse` — the String tax. `parse(String)` routes
///   through `String.getBytes()`, which is a *UTF-8 encode*, not a copy, whenever
///   the String is UTF-16-backed (any non-ASCII content). There is no
///   `reset(String)`, so a String-fed consumer cannot reuse an iterator either.
/// - `string_toChars` vs `string_parse` — the two roads out of a String you are
///   already holding: `toCharArray()` (a copy) into the char path, versus
///   `getBytes()` (an encode) into the byte path. Which wins depends on whether
///   the String is compact, so both rows exist.
/// - `chars_reset` vs `bytes_reset` — the char-path tax. 16-bit lanes move half
///   the payload per word, and field names arrive without widening.
/// - `stream_reset` vs `bytes_reset` — the stream tax. `reset(InputStream)`
///   reads the stream fully (`readAllBytes`) and iterates the resulting array;
///   there is no incremental parse, so this is a copy, not a streaming win.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class SourceBench {

  @Param({"twitter", "solana"})
  private String document;

  private byte[] json;
  private String jsonString;
  private char[] jsonChars;
  private JsonIterator bytesIterator;
  private JsonIterator charsIterator;

  @Setup
  public void setup() {
    json = switch (document) {
      case "solana" -> readGzip("/solana-block.json.gz");
      case "twitter" -> read("/twitter.json");
      default -> throw new IllegalArgumentException("Unknown document: " + document);
    };
    jsonString = new String(json, StandardCharsets.UTF_8);
    jsonChars = jsonString.toCharArray();
    bytesIterator = JsonIterator.parse(json);
    charsIterator = JsonIterator.parse(jsonChars);

    check(bytes_reset(), bytes_parse());
    check(bytes_reset(), string_parse());
    check(bytes_reset(), string_toChars());
    check(bytes_reset(), chars_reset());
    check(bytes_reset(), stream_reset());
  }

  private static byte[] read(final String resource) {
    try (final var in = SourceBench.class.getResourceAsStream(resource)) {
      return in.readAllBytes();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static byte[] readGzip(final String resource) {
    try (final var in = new GZIPInputStream(SourceBench.class.getResourceAsStream(resource))) {
      return in.readAllBytes();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void check(final long a, final long b) {
    if (a != b) {
      throw new IllegalStateException("source walks disagree: " + a + " != " + b);
    }
  }

  private static final ContextFieldBufferPredicate<long[]> FIELD_WALKER = (sum, buf, offset, len, ji) -> {
    sum[0] += len + walk(ji);
    return true;
  };

  /// The same field-name walk IocBench uses, so rows here are comparable with
  /// its fieldWalk* numbers: every field name is delivered to the char buffer,
  /// every value is skipped.
  private static long walk(final JsonIterator ji) {
    return switch (ji.whatIsNext()) {
      case OBJECT -> {
        final long[] sum = new long[1];
        ji.testObject(sum, FIELD_WALKER);
        yield sum[0];
      }
      case ARRAY -> {
        long sum = 0;
        while (ji.readArray()) {
          sum += walk(ji);
        }
        yield sum;
      }
      default -> {
        ji.skip();
        yield 1;
      }
    };
  }

  @Benchmark
  public long bytes_reset() {
    return walk(bytesIterator.reset(json));
  }

  @Benchmark
  public long bytes_parse() {
    return walk(JsonIterator.parse(json));
  }

  @Benchmark
  public long string_parse() {
    return walk(JsonIterator.parse(jsonString));
  }

  /// The other road out of a String: pay `toCharArray()` and take the char path,
  /// rather than pay `getBytes()` and take the byte path (`string_parse`).
  @Benchmark
  public long string_toChars() {
    return walk(JsonIterator.parse(jsonString.toCharArray()));
  }

  @Benchmark
  public long chars_reset() {
    return walk(charsIterator.reset(jsonChars));
  }

  @Benchmark
  public long stream_reset() {
    return walk(bytesIterator.reset(new ByteArrayInputStream(json)));
  }
}
