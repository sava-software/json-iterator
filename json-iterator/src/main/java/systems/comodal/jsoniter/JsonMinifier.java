package systems.comodal.jsoniter;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorShuffle;

import java.util.Arrays;

import static systems.comodal.jsoniter.StructuralIndex.BLOCK;
import static systems.comodal.jsoniter.StructuralIndex.EVEN_BITS;
import static systems.comodal.jsoniter.StructuralIndex.WHITESPACE_TABLE;
import static systems.comodal.jsoniter.StructuralIndex.prefixXor;

/// Vectorized JSON minification, after simdjson's json_minifier: stage-1 mask
/// computation resolves escapes and string boundaries, yielding a 64-bit
/// remove mask per block (whitespace outside of strings).
///
/// Unlike the C++ original, the output stage copies the kept runs between
/// whitespace clusters with System.arraycopy rather than vector
/// compress-stores: [ByteVector#compress(VectorMask)] only has native backing
/// on AVX-512-VBMI2/SVE, and its fallback lowering measured several times
/// slower than scalar code on NEON. Whitespace clusters are few per block, so
/// run copies approach memcpy speed, and blocks with no removable whitespace
/// copy whole.
final class JsonMinifier {

  private static final byte QUOTE = '"';
  private static final byte BACKSLASH = '\\';
  private static final byte LOW_NIBBLE_MASK = 0x0f;

  private long prevInString;
  private long prevEscaped;
  private int dst;

  private JsonMinifier() {
  }

  static byte[] minify(final byte[] json, final int offset, final int len) {
    // Slack: the padded final block can briefly overshoot before the
    // unclosed-string check rejects the document.
    final byte[] out = new byte[len + BLOCK];
    final var minifier = new JsonMinifier();
    final int to = offset + len;
    int i = offset;
    for (; i + BLOCK <= to; i += BLOCK) {
      minifier.block(json, i, out);
    }
    if (i < to) {
      final byte[] lastBlock = new byte[BLOCK];
      Arrays.fill(lastBlock, (byte) ' ');
      System.arraycopy(json, i, lastBlock, 0, to - i);
      minifier.block(lastBlock, 0, out);
    }
    if (minifier.prevInString != 0) {
      throw new JsonException("Unclosed string: a string is opened but never closed.");
    }
    return Arrays.copyOf(out, minifier.dst);
  }

  private void block(final byte[] src, final int srcOffset, final byte[] out) {
    final var species = VectorSupport.BYTE_SPECIES;
    final int lanes = VectorSupport.BYTE_LANES;
    long backslash = 0, quote = 0, whitespace = 0;
    for (int o = srcOffset, shift = 0; shift < BLOCK; o += lanes, shift += lanes) {
      final var chunk = ByteVector.fromArray(species, src, o);
      backslash |= chunk.eq(BACKSLASH).toLong() << shift;
      quote |= chunk.eq(QUOTE).toLong() << shift;
      final VectorShuffle<Byte> lowNibbles = chunk.and(LOW_NIBBLE_MASK).toShuffle();
      whitespace |= chunk.eq(WHITESPACE_TABLE.rearrange(lowNibbles)).toLong() << shift;
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
      prevEscaped = ((oddSequenceStarts >>> 1) + (backslash >>> 1) + ((oddSequenceStarts & backslash) & 1)) >>> 63;
      escaped = (EVEN_BITS ^ (sequencesStartingOnEvenBits << 1)) & followsEscape;
    }
    quote &= ~escaped;
    final long inString = prefixXor(quote) ^ prevInString;
    prevInString = inString >> 63;
    long remove = whitespace & ~inString;
    int d = dst;
    if (remove == 0) {
      System.arraycopy(src, srcOffset, out, d, BLOCK);
      dst = d + BLOCK;
      return;
    }
    int from = srcOffset;
    while (remove != 0) {
      final int runStart = Long.numberOfTrailingZeros(remove);
      final int keepLength = (srcOffset + runStart) - from;
      System.arraycopy(src, from, out, d, keepLength);
      d += keepLength;
      final int runEnd = runStart + Long.numberOfTrailingZeros(~(remove >>> runStart));
      from = srcOffset + runEnd;
      remove = runEnd >= Long.SIZE ? 0 : remove & (-1L << runEnd);
    }
    final int keepLength = (srcOffset + BLOCK) - from;
    System.arraycopy(src, from, out, d, keepLength);
    dst = d + keepLength;
  }
}
