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
import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.IndexedJsonIterator;
import systems.comodal.jsoniter.JsonIterator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

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
/// - `blockParse`: a sava-style DTO parse (see software.sava.rpc's Block):
///   a [FieldBufferPredicate] dispatching on [JsonIterator#fieldEquals] with
///   nested testObject parsers for transaction metas — the inversion-of-control
///   pattern real consumers of this library use.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class SolanaBlockBench {

  private byte[] json;
  private JsonIterator jsonIterator;
  private IndexedJsonIterator jsonIndexed;
  private IndexedJsonIterator jsonIndexedValidating;
  private SimdJsonParser simdParser;

  @Setup
  public void setup() {
    try (final var in = new GZIPInputStream(SolanaBlockBench.class.getResourceAsStream("/solana-block.json.gz"))) {
      json = in.readAllBytes();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    jsonIterator = JsonIterator.parse(json);
    jsonIndexed = IndexedJsonIterator.parse(json);
    jsonIndexedValidating = IndexedJsonIterator.parseValidating(json);
    simdParser = new SimdJsonParser(json.length + 1_024, 1_024);

    check(fullWalk_jsonIterator(), fullWalk_jsonIndexed(), fullWalk_simdjson());
    check(fees_jsonIterator(), fees_jsonIndexed(), fees_simdjson());
    check(blockParse_jsonIterator(), blockParse_jsonIndexed(), blockParse_simdjson());
  }

  private static void check(final long a, final long b, final long c) {
    if (a != b || b != c) {
      throw new IllegalStateException("parsers disagree: " + a + ", " + b + ", " + c);
    }
  }

  // parseOnly

  @Benchmark
  public Object parseOnly_jsonIndexed() {
    return jsonIndexed.reset(json);
  }

  @Benchmark
  public Object parseOnlyValidating_jsonIndexed() {
    return jsonIndexedValidating.reset(json);
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
  public long fullWalk_jsonIndexed() {
    return Walks.walk(jsonIndexed.reset(json));
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
  public long fees_jsonIndexed() {
    return fees(jsonIndexed.reset(json));
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

  // blockParse: sava-style inversion-of-control DTO parse

  private static final class BlockParser implements FieldBufferPredicate {

    private long blockHeight;
    private long blockTime;
    private long parentSlot;
    private String blockHash;
    private String previousBlockHash;
    private long fees;
    private long computeUnits;
    private int numTransactions;

    private static final ContextFieldBufferPredicate<BlockParser> META_PARSER = (parser, buf, offset, len, ji) -> {
      if (fieldEquals("fee", buf, offset, len)) {
        parser.fees += ji.readLong();
      } else if (fieldEquals("computeUnitsConsumed", buf, offset, len)) {
        parser.computeUnits += ji.readLong();
      } else {
        ji.skip();
      }
      return true;
    };

    private static final ContextFieldBufferPredicate<BlockParser> TX_PARSER = (parser, buf, offset, len, ji) -> {
      if (fieldEquals("meta", buf, offset, len)) {
        ji.testObject(parser, META_PARSER);
      } else {
        ji.skip();
      }
      return true;
    };

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("blockHeight", buf, offset, len)) {
        blockHeight = ji.readLong();
      } else if (fieldEquals("blockTime", buf, offset, len)) {
        blockTime = ji.readLong();
      } else if (fieldEquals("parentSlot", buf, offset, len)) {
        parentSlot = ji.readLong();
      } else if (fieldEquals("blockhash", buf, offset, len)) {
        blockHash = ji.readString();
      } else if (fieldEquals("previousBlockhash", buf, offset, len)) {
        previousBlockHash = ji.readString();
      } else if (fieldEquals("transactions", buf, offset, len)) {
        while (ji.readArray()) {
          ++numTransactions;
          ji.testObject(this, TX_PARSER);
        }
      } else {
        ji.skip();
      }
      return true;
    }

    private long checksum() {
      return blockHeight + blockTime + parentSlot
          + blockHash.length() + previousBlockHash.length()
          + fees + computeUnits + numTransactions;
    }
  }

  private static long parseBlock(final JsonIterator ji) {
    ji.skipUntil("result");
    final var parser = new BlockParser();
    ji.testObject(parser);
    return parser.checksum();
  }

  @Benchmark
  public long blockParse_jsonIterator() {
    return parseBlock(jsonIterator.reset(json));
  }

  @Benchmark
  public long blockParse_jsonIndexed() {
    return parseBlock(jsonIndexed.reset(json));
  }

  @Benchmark
  public long blockParse_simdjson() {
    final var result = simdParser.parse(json, json.length).get("result");
    long fees = 0;
    long computeUnits = 0;
    int numTransactions = 0;
    for (final var it = result.get("transactions").arrayIterator(); it.hasNext(); ) {
      final var meta = it.next().get("meta");
      fees += meta.get("fee").asLong();
      computeUnits += meta.get("computeUnitsConsumed").asLong();
      ++numTransactions;
    }
    return result.get("blockHeight").asLong()
        + result.get("blockTime").asLong()
        + result.get("parentSlot").asLong()
        + result.get("blockhash").asString().length()
        + result.get("previousBlockhash").asString().length()
        + fees + computeUnits + numTransactions;
  }
}
