package systems.comodal.jsoniter.jmh;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.simdjson.JsonValue;
import org.simdjson.SimdJsonParser;
import systems.comodal.jsoniter.IndexedJsonIterator;
import systems.comodal.jsoniter.JsonIterator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/// Parses a real Solana mainnet getBlock RPC response (slot 432104870,
/// 1,194 transactions, ~4.7 MiB decompressed; stored gzipped in resources) —
/// representative of the deeply nested, number- and base58-string-heavy
/// payloads this library is used for.
///
/// Workloads:
/// - `parseOnly`: stage-1 structural indexing / simdjson stage 1 + tape.
/// - `fullWalk`: touch every field name and value in the document.
/// - `fees`: sum result.transactions[*].meta.fee, skipping everything else —
///   a realistic selective extraction over a large document.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class SolanaBlockBench {

  private byte[] json;
  private JsonIterator jsonIterator;
  private IndexedJsonIterator indexed;
  private SimdJsonParser simdParser;

  @Setup
  public void setup() {
    try (final var in = new GZIPInputStream(SolanaBlockBench.class.getResourceAsStream("/solana-block.json.gz"))) {
      json = in.readAllBytes();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    jsonIterator = JsonIterator.parse(json);
    indexed = IndexedJsonIterator.parse(json);
    simdParser = new SimdJsonParser(json.length + 1_024, 1_024);

    check(fullWalk_jsonIterator(), fullWalk_indexed(), fullWalk_simdjson());
    check(fees_jsonIterator(), fees_indexed(), fees_simdjson());
  }

  private static void check(final long a, final long b, final long c) {
    if (a != b || b != c) {
      throw new IllegalStateException("parsers disagree: " + a + ", " + b + ", " + c);
    }
  }

  // parseOnly

  @Benchmark
  public Object parseOnly_indexed() {
    return indexed.reset(json);
  }

  @Benchmark
  public Object parseOnly_simdjson() {
    return simdParser.parse(json, json.length);
  }

  // fullWalk

  @Benchmark
  public long fullWalk_jsonIterator() {
    return Walks.walk(jsonIterator.reset(json));
  }

  @Benchmark
  public long fullWalk_indexed() {
    return Walks.walk(indexed.reset(json));
  }

  @Benchmark
  public long fullWalk_simdjson() {
    return Walks.walk(simdParser.parse(json, json.length));
  }

  // fees: selective extraction of result.transactions[*].meta.fee

  @Benchmark
  public long fees_jsonIterator() {
    return fees(jsonIterator.reset(json));
  }

  @Benchmark
  public long fees_indexed() {
    return fees(indexed.reset(json));
  }

  private static long fees(final JsonIterator ji) {
    long fees = 0;
    ji.skipUntil("result").skipUntil("transactions");
    while (ji.readArray()) {
      ji.skipUntil("meta").skipUntil("fee");
      fees += ji.readLong();
      ji.skipRestOfObject().skipRestOfObject();
    }
    return fees;
  }

  @Benchmark
  public long fees_simdjson() {
    final var doc = simdParser.parse(json, json.length);
    long fees = 0;
    for (final var it = doc.get("result").get("transactions").arrayIterator(); it.hasNext(); ) {
      fees += it.next().get("meta").get("fee").asLong();
    }
    return fees;
  }
}
