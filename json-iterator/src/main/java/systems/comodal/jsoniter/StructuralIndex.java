package systems.comodal.jsoniter;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorShuffle;

import java.util.Arrays;

/// Stage 1 of the simdjson algorithm: scan a document range in 64-character
/// blocks with the Vector API and record the position of every structural
/// character and scalar start.
///
/// For each block the scan derives 64-bit masks (one bit per input character):
/// - `backslash`/`escaped`: escape sequences are resolved with the odd/even
///   carry trick so escaped quotes never terminate a string, even across
///   block boundaries.
/// - `quote` and its prefix-XOR: every character between an opening quote
///   (inclusive) and its closing quote (exclusive) is flagged as in-string,
///   which erases structural characters and scalar starts inside strings.
/// - `whitespace` and `op`: for bytes, classified with a 16-entry nibble table
///   and a vector `rearrange`; for chars, with direct 16-bit comparisons
///   (low-byte tricks would misclassify characters like U+2222).
///
/// The recorded indexes are the offsets of `{` `}` `[` `]` `:` `,`, of each
/// string's opening quote, and of the first character of every number,
/// `true`, `false`, and `null`. String contents are fully resolved here and
/// never revisited by stage 2.
///
/// No padding is required: the final partial block is copied into a reusable
/// space-filled scratch block, simdjson style.
final class StructuralIndex {

  static final int BLOCK = 64;

  private static final byte QUOTE = '"';
  private static final byte BACKSLASH = '\\';
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

  private final byte[] lastByteBlock = new byte[BLOCK];
  private final char[] lastCharBlock = new char[BLOCK];

  private int[] indexes = new int[256];
  private int count;
  // carries across blocks
  private long prevInString;
  private long prevEscaped;
  private long prevScalar;
  private long prevStructurals;

  int[] indexes() {
    return indexes;
  }

  int count() {
    return count;
  }

  void index(final byte[] buf, final int from, final int to) {
    begin(to - from);
    int offset = from;
    int last = from;
    for (; offset + BLOCK <= to; offset += BLOCK) {
      byteBlock(buf, offset, offset);
      last = offset;
    }
    if (offset < to) {
      Arrays.fill(lastByteBlock, (byte) ' ');
      System.arraycopy(buf, offset, lastByteBlock, 0, to - offset);
      byteBlock(lastByteBlock, 0, offset);
      last = offset;
    }
    finish(last, to);
  }

  void index(final char[] buf, final int from, final int to) {
    begin(to - from);
    int offset = from;
    int last = from;
    for (; offset + BLOCK <= to; offset += BLOCK) {
      charBlock(buf, offset, offset);
      last = offset;
    }
    if (offset < to) {
      Arrays.fill(lastCharBlock, ' ');
      System.arraycopy(buf, offset, lastCharBlock, 0, to - offset);
      charBlock(lastCharBlock, 0, offset);
      last = offset;
    }
    finish(last, to);
  }

  private void begin(final int len) {
    count = 0;
    prevInString = 0;
    prevEscaped = 0;
    prevScalar = 0;
    prevStructurals = 0;
    if (indexes.length < len + 2) {
      indexes = new int[len + 2]; // worst case is one structural per character, plus two sentinels
    }
  }

  private void byteBlock(final byte[] src, final int srcOffset, final int blockStart) {
    final var species = VectorSupport.BYTE_SPECIES;
    final int lanes = VectorSupport.BYTE_LANES;
    long backslash = 0, quote = 0, whitespace = 0, op = 0;
    for (int o = srcOffset, shift = 0; shift < BLOCK; o += lanes, shift += lanes) {
      final var chunk = ByteVector.fromArray(species, src, o);
      backslash |= chunk.eq(BACKSLASH).toLong() << shift;
      quote |= chunk.eq(QUOTE).toLong() << shift;
      final VectorShuffle<Byte> lowNibbles = chunk.and(LOW_NIBBLE_MASK).toShuffle();
      whitespace |= chunk.eq(WHITESPACE_TABLE.rearrange(lowNibbles)).toLong() << shift;
      op |= chunk.or((byte) 0x20).eq(OP_TABLE.rearrange(lowNibbles)).toLong() << shift;
    }
    block(blockStart, backslash, quote, whitespace, op);
  }

  private void charBlock(final char[] src, final int srcOffset, final int blockStart) {
    final var species = VectorSupport.SHORT_SPECIES;
    final int lanes = VectorSupport.SHORT_LANES;
    long backslash = 0, quote = 0, whitespace = 0, op = 0;
    for (int o = srcOffset, shift = 0; shift < BLOCK; o += lanes, shift += lanes) {
      final var chunk = ShortVector.fromCharArray(species, src, o);
      backslash |= chunk.eq((short) '\\').toLong() << shift;
      quote |= chunk.eq((short) '"').toLong() << shift;
      whitespace |= chunk.eq((short) ' ')
          .or(chunk.eq((short) '\t'))
          .or(chunk.eq((short) '\n'))
          .or(chunk.eq((short) '\r'))
          .toLong() << shift;
      op |= chunk.eq((short) ':')
          .or(chunk.eq((short) ','))
          .or(chunk.eq((short) '{'))
          .or(chunk.eq((short) '}'))
          .or(chunk.eq((short) '['))
          .or(chunk.eq((short) ']'))
          .toLong() << shift;
    }
    block(blockStart, backslash, quote, whitespace, op);
  }

  private void block(final int blockStart, long backslash, long quote, final long whitespace, final long op) {
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
    write(blockStart - BLOCK, prevStructurals);
    prevStructurals = potentialStructuralStart & ~(inString ^ quote);
  }

  private void finish(final int lastBlockStart, final int to) {
    write(lastBlockStart, prevStructurals);
    // Sentinels point just past the document, so navigation past the end reads
    // whatever follows the range and fails through the usual bounds checks.
    indexes[count] = to;
    indexes[count + 1] = to;
    if (prevInString != 0) {
      throw new JsonException("Unclosed string: a string is opened but never closed.");
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
