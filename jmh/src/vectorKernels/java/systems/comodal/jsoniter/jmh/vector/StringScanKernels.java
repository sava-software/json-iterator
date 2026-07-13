package systems.comodal.jsoniter.jmh.vector;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorOperators;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/// The two competing string-scan idioms, extracted verbatim as standalone
/// kernels over clean ascii string content (no escapes/multibyte — the bench
/// controls the data; integrated code hands those off to scalar decoders):
///
/// - `scalar*`: main's current word-at-a-time SWAR loops (BytesJsonIterator
///   parse()/skipPastEndQuote) — the incumbent any integration must beat.
/// - `vector*`: the retired fork's hybrid — a 32-byte SWAR prefix (short
///   strings dominate real JSON and pay vector fixed costs) before
///   anyTrue/firstTrue vector chunks.
///
/// widen kernels return the string length after copying chars into `out`;
/// skip kernels return the index just past the closing quote.
final class StringScanKernels {

  private static final VarHandle TO_LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
  private static final long QUOTE_PATTERN = 0x2222222222222222L;
  private static final long ESCAPE_PATTERN = 0x5C5C5C5C5C5C5C5CL;
  private static final long HIGH_BITS = 0x8080808080808080L;
  private static final int SWAR_PREFIX = 32;
  private static final byte QUOTE = '"';
  private static final byte BACKSLASH = '\\';

  private static long matchPattern(final long input) {
    return ~(((input & 0x7F7F7F7F7F7F7F7FL) + 0x7F7F7F7F7F7F7F7FL) | input | 0x7F7F7F7F7F7F7F7FL);
  }

  private static boolean containsMultiByteOrEscapePattern(final long word) {
    return (word & HIGH_BITS) != 0 || matchPattern(word ^ ESCAPE_PATTERN) != 0;
  }

  // --- main's SWAR idiom ---

  static int scalarWiden(final byte[] buf, final int head, final int tail, final char[] out) {
    int i = head;
    for (int nextOffset = i + Long.BYTES; nextOffset <= tail; ) {
      final long word = (long) TO_LONG.get(buf, i);
      if (containsMultiByteOrEscapePattern(word)) {
        break;
      }
      final long tmp = matchPattern(word ^ QUOTE_PATTERN);
      if (tmp != 0) {
        final int quote = i + ((Long.numberOfTrailingZeros(tmp << 1) >>> 3) - 1);
        final int len = quote - head;
        for (int k = 0; k < len; ++k) {
          out[k] = (char) (buf[head + k] & 0xff);
        }
        return len;
      }
      i = nextOffset;
      nextOffset += Long.BYTES;
    }
    for (; i < tail; ++i) {
      if (buf[i] == QUOTE) {
        final int len = i - head;
        for (int k = 0; k < len; ++k) {
          out[k] = (char) (buf[head + k] & 0xff);
        }
        return len;
      }
    }
    throw new IllegalStateException("no closing quote");
  }

  static int scalarSkip(final byte[] buf, final int head, final int tail) {
    int i = head;
    for (int nextOffset = i + Long.BYTES; nextOffset <= tail; ) {
      final long word = (long) TO_LONG.get(buf, i);
      if (containsMultiByteOrEscapePattern(word)) {
        break;
      }
      final long tmp = matchPattern(word ^ QUOTE_PATTERN);
      if (tmp != 0) {
        return i + (Long.numberOfTrailingZeros(tmp << 1) >>> 3);
      }
      i = nextOffset;
      nextOffset += Long.BYTES;
    }
    for (; i < tail; ++i) {
      if (buf[i] == QUOTE) {
        return i + 1;
      }
    }
    throw new IllegalStateException("no closing quote");
  }

  // --- the fork's hybrid SWAR-prefix + vector idiom ---

  private static void widenChunk(final ByteVector chunk, final char[] out, final int j) {
    ((ShortVector) chunk.convertShape(VectorOperators.B2S, VectorSupport.SHORT_SPECIES, 0)).intoCharArray(out, j);
    ((ShortVector) chunk.convertShape(VectorOperators.B2S, VectorSupport.SHORT_SPECIES, 1)).intoCharArray(out, j + (VectorSupport.BYTE_LANES >> 1));
  }

  static int vectorWiden(final byte[] buf, final int head, final int tail, final char[] out) {
    int j = 0;
    int h = head;
    for (final int prefixEnd = h + SWAR_PREFIX; h + Long.BYTES <= tail && h < prefixEnd; ) {
      final long word = (long) TO_LONG.get(buf, h);
      if (containsMultiByteOrEscapePattern(word)) {
        throw new IllegalStateException("clean ascii expected");
      }
      final long quote = matchPattern(word ^ QUOTE_PATTERN);
      if (quote != 0) {
        final int pos = h + (Long.numberOfTrailingZeros(quote) >>> 3);
        final int len = pos - head;
        for (int k = 0; k < len; ++k) {
          out[k] = (char) (buf[head + k] & 0xff);
        }
        return len;
      }
      h += Long.BYTES;
    }
    if (h > head) {
      j = h - head;
      for (int k = 0; k < j; ++k) {
        out[k] = (char) (buf[head + k] & 0xff);
      }
    }
    final int lanes = VectorSupport.BYTE_LANES;
    while (h + lanes <= tail) {
      final var chunk = ByteVector.fromArray(VectorSupport.BYTE_SPECIES, buf, h);
      final var stopMask = chunk.eq(QUOTE).or(chunk.eq(BACKSLASH)).or(chunk.compare(VectorOperators.LT, (byte) 0));
      if (!stopMask.anyTrue()) {
        widenChunk(chunk, out, j);
        j += lanes;
        h += lanes;
      } else {
        final int n = stopMask.firstTrue();
        for (int i = 0; i < n; ++i) {
          out[j + i] = (char) buf[h + i];
        }
        return j + n;
      }
    }
    for (; h < tail; ++h) {
      if (buf[h] == QUOTE) {
        return j;
      }
      out[j++] = (char) (buf[h] & 0xff);
    }
    throw new IllegalStateException("no closing quote");
  }

  static int vectorSkip(final byte[] buf, final int head, final int tail) {
    int h = head;
    for (final int prefixEnd = h + SWAR_PREFIX; h + Long.BYTES <= tail && h < prefixEnd; ) {
      final long word = (long) TO_LONG.get(buf, h);
      if (containsMultiByteOrEscapePattern(word)) {
        throw new IllegalStateException("clean ascii expected");
      }
      final long quote = matchPattern(word ^ QUOTE_PATTERN);
      if (quote != 0) {
        return h + (Long.numberOfTrailingZeros(quote) >>> 3) + 1;
      }
      h += Long.BYTES;
    }
    final int lanes = VectorSupport.BYTE_LANES;
    int nextOffset = h + lanes;
    while (nextOffset <= tail) {
      final var chunk = ByteVector.fromArray(VectorSupport.BYTE_SPECIES, buf, h);
      final var stopMask = chunk.eq(QUOTE).or(chunk.eq(BACKSLASH)).or(chunk.compare(VectorOperators.LT, (byte) 0));
      if (!stopMask.anyTrue()) {
        h = nextOffset;
        nextOffset += lanes;
      } else {
        return h + stopMask.firstTrue() + 1;
      }
    }
    for (; h < tail; ++h) {
      if (buf[h] == QUOTE) {
        return h + 1;
      }
    }
    throw new IllegalStateException("no closing quote");
  }

  private StringScanKernels() {
  }
}
