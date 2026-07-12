package systems.comodal.jsoniter.jmh;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import systems.comodal.jsoniter.CharBufferToIntFunction;
import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.ContextFieldIndexPredicate;
import systems.comodal.jsoniter.FieldMatcher;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

/// Before/after benchmarks for the sava / idl-src-gen migration shapes, so
/// each pattern's end-to-end payoff — including realistic value consumption
/// that dilutes pure dispatch wins — can be judged on its own:
///
/// - `txMetaWalk`: sava's widest real DTO parse (TxMeta, 13-field dispatch
///   over every transaction of a real Solana block, reading balances and log
///   messages). before: char fieldEquals chain; after: FieldMatcher.
/// - `enumValues`: Commitment-style 3-value enum dispatch over 30k string
///   values. before: applyChars + fieldEquals chain; half: applyChars +
///   public FieldMatcher.match; after: matchString.
/// - `kindDispatch`: Codama-style polymorphic node dispatch, 30 kind names
///   over 10k objects. before: readString() + String switch (the
///   JIT-optimized status quo); after: matchString + int switch.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class MigrationBench {

  private byte[] solanaJson;
  private byte[] enumJson;
  private byte[] kindJson;
  private JsonIterator jsonIterator;

  @Setup
  public void setup() {
    try (final var in = new GZIPInputStream(MigrationBench.class.getResourceAsStream("/solana-block.json.gz"))) {
      solanaJson = in.readAllBytes();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    jsonIterator = JsonIterator.parse(solanaJson);

    final var enumDoc = new StringBuilder(1 << 19).append('[');
    final String[] commitments = {"confirmed", "processed", "finalized"};
    for (int i = 0; i < 30_000; ++i) {
      if (i > 0) {
        enumDoc.append(',');
      }
      enumDoc.append('"').append(commitments[i % commitments.length]).append('"');
    }
    enumJson = enumDoc.append(']').toString().getBytes(StandardCharsets.US_ASCII);

    final var kindDoc = new StringBuilder(1 << 20).append('[');
    for (int i = 0; i < 10_000; ++i) {
      if (i > 0) {
        kindDoc.append(',');
      }
      kindDoc.append("{\"kind\":\"").append(KINDS[i % KINDS.length])
          .append("\",\"name\":\"n").append(i).append("\",\"size\":").append(i).append('}');
    }
    kindJson = kindDoc.append(']').toString().getBytes(StandardCharsets.US_ASCII);

    check(txMetaWalk_charsChain(), txMetaWalk_matcher());
    check(enumValues_charsChain(), enumValues_charsMatch());
    check(enumValues_charsChain(), enumValues_matchString());
    check(kindDispatch_stringSwitch(), kindDispatch_matchString());
  }

  private static void check(final long a, final long b) {
    if (a != b) {
      throw new IllegalStateException("variants disagree: " + a + " != " + b);
    }
  }

  // txMetaWalk: full sava-style TxMeta parse of every transaction meta.

  private static final class MetaSums {

    long computeUnits;
    long costUnits;
    long fees;
    long balances;
    long logLen;

    long checksum() {
      return computeUnits + costUnits + fees + balances + logLen;
    }
  }

  private static long sumLongArray(final JsonIterator ji) {
    long sum = 0;
    while (ji.readArray()) {
      sum += ji.readLong();
    }
    return sum;
  }

  private static long sumLogMessages(final JsonIterator ji) {
    if (ji.whatIsNext() != ValueType.ARRAY) {
      ji.skip();
      return 0;
    }
    long len = 0;
    while (ji.readArray()) {
      len += ji.readString().length();
    }
    return len;
  }

  /// The before shape: sava's TxMeta chain verbatim (13 branches, document
  /// order per the RPC response).
  private static final ContextFieldBufferPredicate<MetaSums> CHARS_META_PARSER = (sums, buf, offset, len, ji) -> {
    if (fieldEquals("err", buf, offset, len)) {
      ji.skip();
    } else if (fieldEquals("computeUnitsConsumed", buf, offset, len)) {
      sums.computeUnits += ji.readLong();
    } else if (fieldEquals("costUnits", buf, offset, len)) {
      sums.costUnits += ji.readLong();
    } else if (fieldEquals("fee", buf, offset, len)) {
      sums.fees += ji.readLong();
    } else if (fieldEquals("preBalances", buf, offset, len)) {
      sums.balances += sumLongArray(ji);
    } else if (fieldEquals("postBalances", buf, offset, len)) {
      sums.balances += sumLongArray(ji);
    } else if (fieldEquals("preTokenBalances", buf, offset, len)) {
      ji.skip();
    } else if (fieldEquals("postTokenBalances", buf, offset, len)) {
      ji.skip();
    } else if (fieldEquals("innerInstructions", buf, offset, len)) {
      ji.skip();
    } else if (fieldEquals("loadedAddresses", buf, offset, len)) {
      ji.skip();
    } else if (fieldEquals("returnData", buf, offset, len)) {
      ji.skip();
    } else if (fieldEquals("logMessages", buf, offset, len)) {
      sums.logLen += sumLogMessages(ji);
    } else if (fieldEquals("rewards", buf, offset, len)) {
      ji.skip();
    } else {
      ji.skip();
    }
    return true;
  };

  private static final FieldMatcher META_FIELDS = FieldMatcher.of(
      "err", "computeUnitsConsumed", "costUnits", "fee", "preBalances", "postBalances",
      "preTokenBalances", "postTokenBalances", "innerInstructions", "loadedAddresses",
      "returnData", "logMessages", "rewards");

  private static final ContextFieldIndexPredicate<MetaSums> MATCHER_META_PARSER = (sums, fieldIndex, ji) -> {
    switch (fieldIndex) {
      case 1 -> sums.computeUnits += ji.readLong();
      case 2 -> sums.costUnits += ji.readLong();
      case 3 -> sums.fees += ji.readLong();
      case 4, 5 -> sums.balances += sumLongArray(ji);
      case 11 -> sums.logLen += sumLogMessages(ji);
      default -> ji.skip(); // err, token balances, instructions, addresses, returnData, rewards, unknown
    }
    return true;
  };

  private interface MetaObjectParser {

    void parse(final JsonIterator ji, final MetaSums sums);
  }

  private long walkMetas(final MetaObjectParser metaParser) {
    final var ji = jsonIterator.reset(solanaJson);
    ji.skipUntil("result").skipUntil("transactions");
    final var sums = new MetaSums();
    while (ji.readArray()) {
      ji.skipUntil("meta");
      metaParser.parse(ji, sums);
      ji.skipRestOfObject();
    }
    return sums.checksum();
  }

  @Benchmark
  public long txMetaWalk_charsChain() {
    return walkMetas((ji, sums) -> ji.testObject(sums, CHARS_META_PARSER));
  }

  @Benchmark
  public long txMetaWalk_matcher() {
    return walkMetas((ji, sums) -> ji.testObject(sums, META_FIELDS, MATCHER_META_PARSER));
  }

  // enumValues: Commitment-style string-value dispatch.

  private static final FieldMatcher COMMITMENTS = FieldMatcher.of("processed", "confirmed", "finalized");

  private static final CharBufferToIntFunction COMMITMENT_CHAIN = (buf, offset, len) -> {
    if (fieldEquals("processed", buf, offset, len)) {
      return 0;
    } else if (fieldEquals("confirmed", buf, offset, len)) {
      return 1;
    } else if (fieldEquals("finalized", buf, offset, len)) {
      return 2;
    } else {
      return -1;
    }
  };

  private static final CharBufferToIntFunction COMMITMENT_MATCH = (buf, offset, len) ->
      COMMITMENTS.match(buf, offset, len);

  @Benchmark
  public long enumValues_charsChain() {
    final var ji = jsonIterator.reset(enumJson);
    long sum = 0;
    while (ji.readArray()) {
      sum += ji.applyCharsAsInt(COMMITMENT_CHAIN);
    }
    return sum;
  }

  @Benchmark
  public long enumValues_charsMatch() {
    final var ji = jsonIterator.reset(enumJson);
    long sum = 0;
    while (ji.readArray()) {
      sum += ji.applyCharsAsInt(COMMITMENT_MATCH);
    }
    return sum;
  }

  @Benchmark
  public long enumValues_matchString() {
    final var ji = jsonIterator.reset(enumJson);
    long sum = 0;
    while (ji.readArray()) {
      sum += ji.matchString(COMMITMENTS);
    }
    return sum;
  }

  // kindDispatch: Codama-style polymorphic dispatch on a "kind" string.

  private static final String[] KINDS = {
      "amountTypeNode", "arrayTypeNode", "booleanTypeNode", "bytesTypeNode", "dateTimeTypeNode",
      "definedTypeLinkNode", "enumEmptyVariantTypeNode", "enumStructVariantTypeNode",
      "enumTupleVariantTypeNode", "enumTypeNode", "fixedSizeTypeNode", "hiddenPrefixTypeNode",
      "hiddenSuffixTypeNode", "mapTypeNode", "numberTypeNode", "optionTypeNode",
      "postOffsetTypeNode", "preOffsetTypeNode", "publicKeyTypeNode", "remainderOptionTypeNode",
      "sentinelTypeNode", "setTypeNode", "sizePrefixTypeNode", "solAmountTypeNode",
      "stringTypeNode", "structFieldTypeNode", "structTypeNode", "tupleTypeNode",
      "zeroableOptionTypeNode", "sizeTypeNode"
  };

  private static final FieldMatcher KIND_MATCHER = FieldMatcher.of(KINDS);

  private static int kindOrdinal(final String kind) {
    return switch (kind) {
      case "amountTypeNode" -> 0;
      case "arrayTypeNode" -> 1;
      case "booleanTypeNode" -> 2;
      case "bytesTypeNode" -> 3;
      case "dateTimeTypeNode" -> 4;
      case "definedTypeLinkNode" -> 5;
      case "enumEmptyVariantTypeNode" -> 6;
      case "enumStructVariantTypeNode" -> 7;
      case "enumTupleVariantTypeNode" -> 8;
      case "enumTypeNode" -> 9;
      case "fixedSizeTypeNode" -> 10;
      case "hiddenPrefixTypeNode" -> 11;
      case "hiddenSuffixTypeNode" -> 12;
      case "mapTypeNode" -> 13;
      case "numberTypeNode" -> 14;
      case "optionTypeNode" -> 15;
      case "postOffsetTypeNode" -> 16;
      case "preOffsetTypeNode" -> 17;
      case "publicKeyTypeNode" -> 18;
      case "remainderOptionTypeNode" -> 19;
      case "sentinelTypeNode" -> 20;
      case "setTypeNode" -> 21;
      case "sizePrefixTypeNode" -> 22;
      case "solAmountTypeNode" -> 23;
      case "stringTypeNode" -> 24;
      case "structFieldTypeNode" -> 25;
      case "structTypeNode" -> 26;
      case "tupleTypeNode" -> 27;
      case "zeroableOptionTypeNode" -> 28;
      case "sizeTypeNode" -> 29;
      default -> -1;
    };
  }

  @Benchmark
  public long kindDispatch_stringSwitch() {
    final var ji = jsonIterator.reset(kindJson);
    long sum = 0;
    while (ji.readArray()) {
      ji.skipUntil("kind");
      sum += kindOrdinal(ji.readString());
      ji.skipRestOfObject();
    }
    return sum;
  }

  @Benchmark
  public long kindDispatch_matchString() {
    final var ji = jsonIterator.reset(kindJson);
    long sum = 0;
    while (ji.readArray()) {
      ji.skipUntil("kind");
      sum += ji.matchString(KIND_MATCHER);
      ji.skipRestOfObject();
    }
    return sum;
  }
}
