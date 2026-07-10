package systems.comodal.jsoniter;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShuffle;

/// Stage 1 of the simdjson algorithm: scan the document in 64-byte blocks with
/// the Vector API and record the position of every structural character and
/// scalar start.
///
/// For each block the scan derives 64-bit masks (one bit per input byte):
/// - `backslash`/`escaped`: escape sequences are resolved with the odd/even
///   carry trick so escaped quotes never terminate a string, even across
///   block boundaries.
/// - `quote` and its prefix-XOR: every byte between an opening quote
///   (inclusive) and its closing quote (exclusive) is flagged as in-string,
///   which erases structural characters and scalar starts inside strings.
/// - `whitespace` and `op`: classified with a 16-entry nibble table and a
///   vector `rearrange`, so `{` `}` `[` `]` `:` `,` and JSON whitespace are
///   recognized in one comparison each.
///
/// The recorded indexes are the byte offsets of `{` `}` `[` `]` `:` `,`, of
/// each string's opening quote, and of the first byte of every number,
/// `true`, `false`, and `null`. String contents are fully resolved here and
/// never revisited by stage 2.
///
/// The buffer handed to [#index(byte[],int)] must be padded with at least
/// [#BLOCK] space characters past `len`.
final class StructuralIndex {

  static final int BLOCK = 64;

  private static final byte QUOTE = '"';
  private static final byte BACKSLASH = '\\';
  private static final byte LAST_CONTROL_CHARACTER = 0x1F;
  private static final byte LOW_NIBBLE_MASK = 0x0f;
  private static final long EVEN_BITS = 0x5555555555555555L;
  // WHITESPACE_TABLE[c & 0x0f] == c only when c is one of ' ', '\t', '\n', '\r'.
  private static final ByteVector WHITESPACE_TABLE = repeat(new byte[]{' ', 100, 100, 100, 17, 100, 113, 2, 100, '\t', '\n', 112, 100, '\r', 100, 100});
  // OP_TABLE[c & 0x0f] == (c | 0x20) only when c is one of ':', ',', '{', '}', '[', ']'.
  private static final ByteVector OP_TABLE = repeat(new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, ':', '{', ',', '}', 0, 0});

  private static ByteVector repeat(final byte[] nibbleTable) {
    final byte[] repeated = new byte[VectorSupport.BYTE_LANES];
    for (int i = 0; i < repeated.length; i += nibbleTable.length) {
      System.arraycopy(nibbleTable, 0, repeated, i, nibbleTable.length);
    }
    return ByteVector.fromArray(VectorSupport.BYTE_SPECIES, repeated, 0);
  }

  private int[] indexes = new int[256];
  private int count;

  int[] indexes() {
    return indexes;
  }

  int count() {
    return count;
  }

  void index(final byte[] buf, final int len) {
    count = 0;
    if (indexes.length < len + 2) {
      indexes = new int[len + 2]; // worst case is one structural per byte, plus two sentinels
    }
    final var species = VectorSupport.BYTE_SPECIES;
    final int lanes = VectorSupport.BYTE_LANES;
    long prevInString = 0;
    long prevEscaped = 0;
    long prevScalar = 0;
    long prevStructurals = 0;
    long unescapedError = 0;
    final int numBlocks = (len + BLOCK - 1) / BLOCK;
    for (int block = 0, offset = 0; block < numBlocks; ++block, offset += BLOCK) {
      long backslash = 0, quote = 0, whitespace = 0, op = 0, unescaped = 0;
      for (int o = offset, shift = 0; shift < BLOCK; o += lanes, shift += lanes) {
        final var chunk = ByteVector.fromArray(species, buf, o);
        backslash |= chunk.eq(BACKSLASH).toLong() << shift;
        quote |= chunk.eq(QUOTE).toLong() << shift;
        unescaped |= chunk.compare(VectorOperators.ULE, LAST_CONTROL_CHARACTER).toLong() << shift;
        final VectorShuffle<Byte> lowNibbles = chunk.and(LOW_NIBBLE_MASK).toShuffle();
        whitespace |= chunk.eq(WHITESPACE_TABLE.rearrange(lowNibbles)).toLong() << shift;
        op |= chunk.or((byte) 0x20).eq(OP_TABLE.rearrange(lowNibbles)).toLong() << shift;
      }
      long escaped;
      if (backslash == 0) {
        escaped = prevEscaped;
        prevEscaped = 0;
      } else {
        backslash &= ~prevEscaped;
        final long followsEscape = (backslash << 1) | prevEscaped;
        final long oddSequenceStarts = backslash & ~EVEN_BITS & ~followsEscape;
        final long sequencesStartingOnEvenBits = oddSequenceStarts + backslash;
        // The carry out of the unsigned addition above is the escape state for the next block.
        // Overflow detection per 'Hacker's Delight, Second Edition', Chapter 2-13.
        prevEscaped = ((oddSequenceStarts >>> 1) + (backslash >>> 1) + ((oddSequenceStarts & backslash) & 1)) >>> 63;
        escaped = (EVEN_BITS ^ (sequencesStartingOnEvenBits << 1)) & followsEscape;
      }
      quote &= ~escaped;
      final long inString = prefixXor(quote) ^ prevInString;
      prevInString = inString >> 63;
      final long scalar = ~(op | whitespace);
      final long nonQuoteScalar = scalar & ~quote;
      final long followsNonQuoteScalar = (nonQuoteScalar << 1) | prevScalar;
      prevScalar = nonQuoteScalar >>> 63;
      final long potentialStructuralStart = op | (scalar & ~followsNonQuoteScalar);
      write(offset - BLOCK, prevStructurals);
      prevStructurals = potentialStructuralStart & ~(inString ^ quote);
      unescapedError |= unescaped & inString;
    }
    write((numBlocks - 1) * BLOCK, prevStructurals);
    // Sentinels point at the padding space just past the document, which reads
    // as an INVALID token so navigation past the end fails fast.
    indexes[count] = len;
    indexes[count + 1] = len;
    if (prevInString != 0) {
      throw new JsonException("Unclosed string: a string is opened but never closed.");
    }
    if (unescapedError != 0) {
      throw new JsonException("Unescaped control character within a string.");
    }
  }

  private void write(final int base, long bits) {
    for (int i = count; bits != 0; bits &= bits - 1) {
      indexes[i++] = base + Long.numberOfTrailingZeros(bits);
      count = i;
    }
  }

  /// Carry-less multiply by ~0: flips the running in-string state at every quote.
  private static long prefixXor(long bitmask) {
    bitmask ^= bitmask << 1;
    bitmask ^= bitmask << 2;
    bitmask ^= bitmask << 4;
    bitmask ^= bitmask << 8;
    bitmask ^= bitmask << 16;
    bitmask ^= bitmask << 32;
    return bitmask;
  }
}
