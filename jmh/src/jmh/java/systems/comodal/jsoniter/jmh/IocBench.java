package systems.comodal.jsoniter.jmh;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.ContextFieldIndexMaskedPredicate;
import systems.comodal.jsoniter.ContextFieldIndexPredicate;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.FieldMatcher;
import systems.comodal.jsoniter.JsonIterator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

/// Compares the char-buffer inversion-of-control API (testObject +
/// FieldBufferPredicate + fieldEquals(String, char[])) against FieldMatcher
/// dispatch, which resolves field names to an int index against the
/// underlying buffer without widening or copying.
///
/// Workloads over two real documents (a ~4.7 MiB Solana getBlock response and
/// a ~600 KiB twitter search response):
/// - `blockParse`: a sava-style DTO parse — nested field dispatch against a
///   small set of expected names, skipping everything else.
/// - `fieldWalk`: recursively visits every object field name in the document
///   and skips every value — isolates field-name delivery + dispatch cost.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class IocBench {

  private byte[] solanaJson;
  private byte[] twitterJson;
  private char[] twitterChars;
  private JsonIterator jsonIterator;
  private JsonIterator charsIterator;

  @Setup
  public void setup() {
    try (final var in = new GZIPInputStream(IocBench.class.getResourceAsStream("/solana-block.json.gz"))) {
      solanaJson = in.readAllBytes();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    try (final var in = IocBench.class.getResourceAsStream("/twitter.json")) {
      twitterJson = in.readAllBytes();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    jsonIterator = JsonIterator.parse(solanaJson);
    twitterChars = new String(twitterJson, StandardCharsets.UTF_8).toCharArray();
    charsIterator = JsonIterator.parse(twitterChars);

    final var names = new LinkedHashSet<String>();
    collectNames(jsonIterator.reset(twitterJson), names);
    twitterNames = names.toArray(String[]::new);
    twitterMatcher = FieldMatcher.of(twitterNames);

    check(blockParse_chars(), blockParse_matcher());
    check(blockParse_chars(), blockParse_matcherMasked());
    check(dispatchTwitter_charsLinear(), dispatchTwitter_matcher());
    check(dispatchTwitter_matcher(), dispatchTwitterChars_matcher());
    check(dispatchTwitter_matcher(), dispatchTwitterChars_charsLinear());
    check(valueDispatchTwitter_matchString(), valueDispatchTwitter_applyChars());
  }

  private static void collectNames(final JsonIterator ji, final LinkedHashSet<String> names) {
    switch (ji.whatIsNext()) {
      case OBJECT -> ji.testObject(names, (ctx, buf, offset, len, ji2) -> {
        ctx.add(new String(buf, offset, len));
        collectNames(ji2, ctx);
        return true;
      });
      case ARRAY -> {
        while (ji.readArray()) {
          collectNames(ji, names);
        }
      }
      default -> ji.skip();
    }
  }

  private static void check(final long a, final long b) {
    if (a != b) {
      throw new IllegalStateException("char and byte walks disagree: " + a + " != " + b);
    }
  }

  // fieldWalk: touch every object field name, skip every value.

  private static final ContextFieldBufferPredicate<long[]> CHAR_FIELD_WALKER = (sum, buf, offset, len, ji) -> {
    sum[0] += len + walkChars(ji);
    return true;
  };

  private static long walkChars(final JsonIterator ji) {
    return switch (ji.whatIsNext()) {
      case OBJECT -> {
        final long[] sum = new long[1];
        ji.testObject(sum, CHAR_FIELD_WALKER);
        yield sum[0];
      }
      case ARRAY -> {
        long sum = 0;
        while (ji.readArray()) {
          sum += walkChars(ji);
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
  public long fieldWalkSolana_chars() {
    return walkChars(jsonIterator.reset(solanaJson));
  }

  @Benchmark
  public long fieldWalkTwitter_chars() {
    return walkChars(jsonIterator.reset(twitterJson));
  }

  // dispatchTwitter: resolve every field name in the document to its index
  // among all 94 names that occur in it — a linear fieldEquals chain (the
  // sava/idl-src-gen shape) vs a single FieldMatcher lookup. Isolates
  // dispatch cost at a field-set size where O(K) chains hurt.

  private String[] twitterNames;
  private FieldMatcher twitterMatcher;

  private int linearIndex(final char[] buf, final int offset, final int len) {
    final String[] names = twitterNames;
    for (int i = 0; i < names.length; ++i) {
      if (fieldEquals(names[i], buf, offset, len)) {
        return i;
      }
    }
    return -1;
  }

  private final ContextFieldBufferPredicate<long[]> linearDispatchWalker = (sum, buf, offset, len, ji) -> {
    sum[0] += linearIndex(buf, offset, len) + dispatchLinearWalk(ji);
    return true;
  };

  private long dispatchLinearWalk(final JsonIterator ji) {
    return switch (ji.whatIsNext()) {
      case OBJECT -> {
        final long[] sum = new long[1];
        ji.testObject(sum, linearDispatchWalker);
        yield sum[0];
      }
      case ARRAY -> {
        long sum = 0;
        while (ji.readArray()) {
          sum += dispatchLinearWalk(ji);
        }
        yield sum;
      }
      default -> {
        ji.skip();
        yield 1;
      }
    };
  }

  private final ContextFieldIndexPredicate<long[]> matcherDispatchWalker = (sum, fieldIndex, ji) -> {
    sum[0] += fieldIndex + dispatchMatcherWalk(ji);
    return true;
  };

  private long dispatchMatcherWalk(final JsonIterator ji) {
    return switch (ji.whatIsNext()) {
      case OBJECT -> {
        final long[] sum = new long[1];
        ji.testObject(sum, twitterMatcher, matcherDispatchWalker);
        yield sum[0];
      }
      case ARRAY -> {
        long sum = 0;
        while (ji.readArray()) {
          sum += dispatchMatcherWalk(ji);
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
  public long dispatchTwitter_charsLinear() {
    return dispatchLinearWalk(jsonIterator.reset(twitterJson));
  }

  @Benchmark
  public long dispatchTwitter_matcher() {
    return dispatchMatcherWalk(jsonIterator.reset(twitterJson));
  }

  // valueDispatch: resolve every STRING VALUE in the document against the
  // 94-name matcher — the enum/discriminator pattern. matchString rides the
  // zero-copy byte span; applyCharsAsInt + FieldMatcher.match pays the char
  // widening first.

  private final ContextFieldIndexPredicate<long[]> valueMatchStringWalker = (sum, fieldIndex, ji) -> {
    sum[0] += fieldIndex + valueMatchStringWalk(ji);
    return true;
  };

  private long valueMatchStringWalk(final JsonIterator ji) {
    return switch (ji.whatIsNext()) {
      case OBJECT -> {
        final long[] sum = new long[1];
        ji.testObject(sum, twitterMatcher, valueMatchStringWalker);
        yield sum[0];
      }
      case ARRAY -> {
        long sum = 0;
        while (ji.readArray()) {
          sum += valueMatchStringWalk(ji);
        }
        yield sum;
      }
      case STRING -> ji.matchString(twitterMatcher);
      default -> {
        ji.skip();
        yield 1;
      }
    };
  }

  private final ContextFieldIndexPredicate<long[]> valueApplyCharsWalker = (sum, fieldIndex, ji) -> {
    sum[0] += fieldIndex + valueApplyCharsWalk(ji);
    return true;
  };

  private long valueApplyCharsWalk(final JsonIterator ji) {
    return switch (ji.whatIsNext()) {
      case OBJECT -> {
        final long[] sum = new long[1];
        ji.testObject(sum, twitterMatcher, valueApplyCharsWalker);
        yield sum[0];
      }
      case ARRAY -> {
        long sum = 0;
        while (ji.readArray()) {
          sum += valueApplyCharsWalk(ji);
        }
        yield sum;
      }
      case STRING -> ji.applyCharsAsInt((buf, offset, len) -> twitterMatcher.match(buf, offset, len));
      default -> {
        ji.skip();
        yield 1;
      }
    };
  }

  @Benchmark
  public long valueDispatchTwitter_matchString() {
    return valueMatchStringWalk(jsonIterator.reset(twitterJson));
  }

  @Benchmark
  public long valueDispatchTwitter_applyChars() {
    return valueApplyCharsWalk(jsonIterator.reset(twitterJson));
  }

  // Same workloads over a char[] input: both paths match the char span the
  // chars iterator exposes zero-copy.

  @Benchmark
  public long dispatchTwitterChars_charsLinear() {
    return dispatchLinearWalk(charsIterator.reset(twitterChars));
  }

  @Benchmark
  public long dispatchTwitterChars_matcher() {
    return dispatchMatcherWalk(charsIterator.reset(twitterChars));
  }

  // blockParse: sava-style inversion-of-control DTO parse of the Solana
  // block — the pattern real consumers of this library use.

  private static final class CharBlockParser implements FieldBufferPredicate {

    private long blockHeight;
    private long blockTime;
    private long parentSlot;
    private String blockHash;
    private String previousBlockHash;
    private long fees;
    private long computeUnits;
    private int numTransactions;

    private static final ContextFieldBufferPredicate<CharBlockParser> META_PARSER = (parser, buf, offset, len, ji) -> {
      if (fieldEquals("fee", buf, offset, len)) {
        parser.fees += ji.readLong();
      } else if (fieldEquals("computeUnitsConsumed", buf, offset, len)) {
        parser.computeUnits += ji.readLong();
      } else {
        ji.skip();
      }
      return true;
    };

    private static final ContextFieldBufferPredicate<CharBlockParser> TX_PARSER = (parser, buf, offset, len, ji) -> {
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

  private static final FieldMatcher BLOCK_FIELDS = FieldMatcher.of(
      "blockHeight", "blockTime", "parentSlot", "blockhash", "previousBlockhash", "transactions");
  private static final FieldMatcher TX_FIELDS = FieldMatcher.of("meta");
  private static final FieldMatcher META_FIELDS = FieldMatcher.of("fee", "computeUnitsConsumed");

  private static final class MatcherBlockParser {

    private long blockHeight;
    private long blockTime;
    private long parentSlot;
    private String blockHash;
    private String previousBlockHash;
    private long fees;
    private long computeUnits;
    private int numTransactions;
    private boolean metaBroke;

    private static final ContextFieldIndexPredicate<MatcherBlockParser> META_PARSER = (parser, fieldIndex, ji) -> {
      switch (fieldIndex) {
        case 0 -> parser.fees += ji.readLong();
        case 1 -> parser.computeUnits += ji.readLong();
        default -> ji.skip();
      }
      return true;
    };

    private static final ContextFieldIndexPredicate<MatcherBlockParser> TX_PARSER = (parser, fieldIndex, ji) -> {
      if (fieldIndex == 0) {
        ji.testObject(parser, META_FIELDS, META_PARSER);
      } else {
        ji.skip();
      }
      return true;
    };

    private static final ContextFieldIndexPredicate<MatcherBlockParser> BLOCK_PARSER = (parser, fieldIndex, ji) -> {
      switch (fieldIndex) {
        case 0 -> parser.blockHeight = ji.readLong();
        case 1 -> parser.blockTime = ji.readLong();
        case 2 -> parser.parentSlot = ji.readLong();
        case 3 -> parser.blockHash = ji.readString();
        case 4 -> parser.previousBlockHash = ji.readString();
        case 5 -> {
          while (ji.readArray()) {
            ++parser.numTransactions;
            ji.testObject(parser, TX_FIELDS, TX_PARSER);
          }
        }
        default -> ji.skip();
      }
      return true;
    };

    private long checksum() {
      return blockHeight + blockTime + parentSlot
          + blockHash.length() + previousBlockHash.length()
          + fees + computeUnits + numTransactions;
    }
  }

  /// Masked early-out variant: stop scanning each meta object once both
  /// wanted fields are seen, skipping the (large) remainder wholesale.
  /// The predicate records the break-out in the context because testObject
  /// returns identically on break-out and normal completion.
  private static final ContextFieldIndexMaskedPredicate<MatcherBlockParser> META_PARSER_MASKED = (parser, mask, fieldIndex, ji) -> {
    switch (fieldIndex) {
      case 0 -> {
        parser.fees += ji.readLong();
        if ((mask |= 1) == 3) {
          parser.metaBroke = true;
          return ContextFieldIndexMaskedPredicate.BREAK_OUT;
        }
      }
      case 1 -> {
        parser.computeUnits += ji.readLong();
        if ((mask |= 2) == 3) {
          parser.metaBroke = true;
          return ContextFieldIndexMaskedPredicate.BREAK_OUT;
        }
      }
      default -> ji.skip();
    }
    return mask;
  };

  private static final ContextFieldIndexPredicate<MatcherBlockParser> TX_PARSER_MASKED = (parser, fieldIndex, ji) -> {
    if (fieldIndex == 0) {
      ji.testObject(parser, META_FIELDS, META_PARSER_MASKED);
      if (parser.metaBroke) {
        parser.metaBroke = false;
        ji.skipRestOfObject();
      }
    } else {
      ji.skip();
    }
    return true;
  };

  private static final ContextFieldIndexPredicate<MatcherBlockParser> BLOCK_PARSER_MASKED = (parser, fieldIndex, ji) -> {
    switch (fieldIndex) {
      case 0 -> parser.blockHeight = ji.readLong();
      case 1 -> parser.blockTime = ji.readLong();
      case 2 -> parser.parentSlot = ji.readLong();
      case 3 -> parser.blockHash = ji.readString();
      case 4 -> parser.previousBlockHash = ji.readString();
      case 5 -> {
        while (ji.readArray()) {
          ++parser.numTransactions;
          ji.testObject(parser, TX_FIELDS, TX_PARSER_MASKED);
        }
      }
      default -> ji.skip();
    }
    return true;
  };

  @Benchmark
  public long blockParse_matcherMasked() {
    final var ji = jsonIterator.reset(solanaJson);
    ji.skipUntil("result");
    final var parser = new MatcherBlockParser();
    ji.testObject(parser, BLOCK_FIELDS, BLOCK_PARSER_MASKED);
    return parser.checksum();
  }

  @Benchmark
  public long blockParse_matcher() {
    final var ji = jsonIterator.reset(solanaJson);
    ji.skipUntil("result");
    final var parser = new MatcherBlockParser();
    ji.testObject(parser, BLOCK_FIELDS, MatcherBlockParser.BLOCK_PARSER);
    return parser.checksum();
  }

  @Benchmark
  public long blockParse_chars() {
    final var ji = jsonIterator.reset(solanaJson);
    ji.skipUntil("result");
    final var parser = new CharBlockParser();
    ji.testObject(parser);
    return parser.checksum();
  }
}
