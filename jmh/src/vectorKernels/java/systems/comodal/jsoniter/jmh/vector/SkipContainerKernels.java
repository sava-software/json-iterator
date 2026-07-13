package systems.comodal.jsoniter.jmh.vector;

import jdk.incubator.vector.ByteVector;

/// Container-skipping kernels: main's per-byte level-counting walk versus the
/// retired fork's vector walk (one OR-combined mask per chunk, bits consumed
/// via numberOfTrailingZeros — the single-extraction idiom). Strings inside
/// the container are skipped with the matching Question-2 scan kernel so each
/// variant is internally consistent. Kernels start just inside the opening
/// bracket with level 1 and return the index just past the closing bracket.
final class SkipContainerKernels {

  private static final byte QUOTE = '"';

  static int scalarSkipContainer(final byte[] buf, final int head, final int tail, final byte open, final byte close) {
    int level = 1;
    for (int i = head; i < tail; ++i) {
      final byte c = buf[i];
      if (c == QUOTE) {
        i = StringScanKernels.scalarSkip(buf, i + 1, tail) - 1;
      } else if (c == open) {
        ++level;
      } else if (c == close && --level == 0) {
        return i + 1;
      }
    }
    throw new IllegalStateException("incomplete container");
  }

  static int vectorSkipContainer(final byte[] buf, final int head, final int tail, final byte open, final byte close) {
    int level = 1;
    final int lanes = VectorSupport.BYTE_LANES;
    int h = head;
    outer:
    while (h + lanes <= tail) {
      final var chunk = ByteVector.fromArray(VectorSupport.BYTE_SPECIES, buf, h);
      long bits = chunk.eq(open).or(chunk.eq(close)).or(chunk.eq(QUOTE)).toLong();
      while (bits != 0) {
        final int n = Long.numberOfTrailingZeros(bits);
        final byte c = buf[h + n];
        if (c == QUOTE) {
          h = StringScanKernels.vectorSkip(buf, h + n + 1, tail);
          continue outer;
        } else if (c == open) {
          ++level;
        } else if (--level == 0) {
          return h + n + 1;
        }
        bits &= bits - 1;
      }
      h += lanes;
    }
    for (; h < tail; ++h) {
      final byte c = buf[h];
      if (c == QUOTE) {
        h = StringScanKernels.scalarSkip(buf, h + 1, tail) - 1;
      } else if (c == open) {
        ++level;
      } else if (c == close && --level == 0) {
        return h + 1;
      }
    }
    throw new IllegalStateException("incomplete container");
  }

  private SkipContainerKernels() {
  }
}
