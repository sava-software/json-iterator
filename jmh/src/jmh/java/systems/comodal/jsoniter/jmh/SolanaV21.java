package systems.comodal.jsoniter.jmh;

import jsoniter.v21.ContextFieldBufferPredicate;
import jsoniter.v21.FieldBufferPredicate;
import jsoniter.v21.JsonIterator;

import static jsoniter.v21.JsonIterator.fieldEquals;

/// [SolanaBlockBench] selective workloads duplicated against the relocated
/// published 21.1.0 baseline (package jsoniter.v21); bodies are identical to
/// the current-version equivalents in [SolanaBlockBench].
final class SolanaV21 {

  private SolanaV21() {
  }

  static long fees(final JsonIterator ji) {
    long fees = 0;
    ji.skipUntil("result").skipUntil("transactions");
    while (ji.readArray()) {
      ji.skipUntil("meta").skipUntil("fee");
      fees += ji.readLong();
      ji.skipRestOfObject().skipRestOfObject();
    }
    return fees;
  }

  static long parseBlock(final JsonIterator ji) {
    ji.skipUntil("result");
    final var parser = new BlockParser();
    ji.testObject(parser);
    return parser.checksum();
  }

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
}
