package systems.comodal.jsoniter.jmh.vector;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorOperators;

/// The escape/multibyte string decoders, extracted verbatim: main's scalar
/// byte-at-a-time parseMultiByteString versus the retired fork's variant
/// that bulk-copies clean ascii runs between stops with vector chunks. This
/// path decodes ALL non-ascii and escaped string content — the parser must
/// serve the breadth of JSON in the wild (international scripts, escape
/// density, run lengths), not just the surveyed consumers' content shapes.
/// Both consume from after the opening quote through the closing quote and
/// return the decoded char length; `out` must be pre-sized by the caller.
final class MultiByteDecodeKernels {

  private static final byte QUOTE = '"';
  private static final byte BACKSLASH = '\\';

  private static void widenChunk(final ByteVector chunk, final char[] out, final int j) {
    ((ShortVector) chunk.convertShape(VectorOperators.B2S, VectorSupport.SHORT_SPECIES, 0)).intoCharArray(out, j);
    ((ShortVector) chunk.convertShape(VectorOperators.B2S, VectorSupport.SHORT_SPECIES, 1)).intoCharArray(out, j + (VectorSupport.BYTE_LANES >> 1));
  }

  static int decodeScalar(final byte[] buf, int head, final int tail, final char[] out, int j) {
    boolean isExpectingLowSurrogate = false;
    for (int bc; head < tail; ) {
      bc = buf[head++];
      if (bc == '"') {
        return j;
      } else if (bc == '\\') {
        if (head == tail) {
          break;
        }
        bc = buf[head++];
        switch (bc) {
          case 'b':
            bc = '\b';
            break;
          case 't':
            bc = '\t';
            break;
          case 'n':
            bc = '\n';
            break;
          case 'f':
            bc = '\f';
            break;
          case 'r':
            bc = '\r';
            break;
          case '"':
          case '/':
          case '\\':
            break;
          case 'u':
            if (head == tail) {
              throw new IllegalStateException("incomplete string");
            }
            bc = (decodeHex(buf[head++]) << 12);
            if (head == tail) {
              throw new IllegalStateException("incomplete string");
            }
            bc += (decodeHex(buf[head++]) << 8);
            if (head == tail) {
              throw new IllegalStateException("incomplete string");
            }
            bc += (decodeHex(buf[head++]) << 4);
            if (head == tail) {
              throw new IllegalStateException("incomplete string");
            }
            bc += decodeHex(buf[head++]);
            if (isExpectingLowSurrogate) {
              if (Character.isLowSurrogate((char) bc)) {
                isExpectingLowSurrogate = false;
              } else {
                throw new IllegalStateException("invalid surrogate");
              }
            } else if (Character.isHighSurrogate((char) bc)) {
              isExpectingLowSurrogate = true;
            } else if (Character.isLowSurrogate((char) bc)) {
              throw new IllegalStateException("invalid surrogate");
            }
            break;
          default:
            throw new IllegalStateException("invalid escape character: " + bc);
        }
      } else if ((bc & 0x80) != 0) {
        if (head == tail) {
          break;
        }
        final int u2 = buf[head++];
        if ((bc & 0xE0) == 0xC0) {
          bc = ((bc & 0x1F) << 6) + (u2 & 0x3F);
        } else {
          if (head == tail) {
            break;
          }
          final int u3 = buf[head++];
          if ((bc & 0xF0) == 0xE0) {
            bc = ((bc & 0x0F) << 12) + ((u2 & 0x3F) << 6) + (u3 & 0x3F);
          } else {
            if (head == tail) {
              break;
            }
            final int u4 = buf[head++];
            if ((bc & 0xF8) == 0xF0) {
              bc = ((bc & 0x07) << 18) + ((u2 & 0x3F) << 12) + ((u3 & 0x3F) << 6) + (u4 & 0x3F);
            } else {
              throw new IllegalStateException("invalid unicode character");
            }
            if (bc >= 0x10000) {
              // check if valid unicode
              if (bc >= 0x110000) {
                throw new IllegalStateException("invalid unicode character");
              }
              // split surrogates
              final int sup = bc - 0x10000;
              if (false) {
                
              }
              out[j++] = (char) ((sup >>> 10) + 0xd800);
              if (false) {
                
              }
              out[j++] = (char) ((sup & 0x3ff) + 0xdc00);
              continue;
            }
          }
        }
      }
      if (false) {
        
      }
      out[j++] = (char) bc;
    }
    throw new IllegalStateException("incomplete string");
  }


  static int decodeVector(final byte[] buf, int head, final int tail, final char[] out, int j) {
    boolean isExpectingLowSurrogate = false;
    final int lanes = VectorSupport.BYTE_LANES;
    for (int bc; head < tail; ) {
      // Bulk-copy runs of clean ASCII between escapes / multi-byte characters.
      while (head + lanes <= tail) {
        final var chunk = ByteVector.fromArray(VectorSupport.BYTE_SPECIES, buf, head);
        final var stopMask = chunk.eq(QUOTE)
            .or(chunk.eq(BACKSLASH))
            .or(chunk.compare(VectorOperators.LT, (byte) 0));
        final long stop = stopMask.anyTrue() ? stopMask.toLong() : 0;
        if (stop == 0) {
          
          widenChunk(chunk, out, j);
          j += lanes;
          head += lanes;
        } else {
          final int n = Long.numberOfTrailingZeros(stop);
          
          for (int i = 0; i < n; ++i) {
            out[j + i] = (char) buf[head + i];
          }
          j += n;
          head += n;
          break;
        }
      }
      if (head == tail) {
        break;
      }
      bc = buf[head++];
      if (bc == '"') {
        return j;
      } else if (bc == '\\') {
        if (head == tail) {
          break;
        }
        bc = buf[head++];
        switch (bc) {
          case 'b':
            bc = '\b';
            break;
          case 't':
            bc = '\t';
            break;
          case 'n':
            bc = '\n';
            break;
          case 'f':
            bc = '\f';
            break;
          case 'r':
            bc = '\r';
            break;
          case '"':
          case '/':
          case '\\':
            break;
          case 'u':
            if (head == tail) {
              throw new IllegalStateException("incomplete string");
            }
            bc = (decodeHex(buf[head++]) << 12);
            if (head == tail) {
              throw new IllegalStateException("incomplete string");
            }
            bc += (decodeHex(buf[head++]) << 8);
            if (head == tail) {
              throw new IllegalStateException("incomplete string");
            }
            bc += (decodeHex(buf[head++]) << 4);
            if (head == tail) {
              throw new IllegalStateException("incomplete string");
            }
            bc += decodeHex(buf[head++]);
            if (isExpectingLowSurrogate) {
              if (Character.isLowSurrogate((char) bc)) {
                isExpectingLowSurrogate = false;
              } else {
                throw new IllegalStateException("invalid surrogate");
              }
            } else if (Character.isHighSurrogate((char) bc)) {
              isExpectingLowSurrogate = true;
            } else if (Character.isLowSurrogate((char) bc)) {
              throw new IllegalStateException("invalid surrogate");
            }
            break;
          default:
            throw new IllegalStateException("invalid escape character: " + bc);
        }
      } else if ((bc & 0x80) != 0) {
        if (head == tail) {
          break;
        }
        final int u2 = buf[head++];
        if ((bc & 0xE0) == 0xC0) {
          bc = ((bc & 0x1F) << 6) + (u2 & 0x3F);
        } else {
          if (head == tail) {
            break;
          }
          final int u3 = buf[head++];
          if ((bc & 0xF0) == 0xE0) {
            bc = ((bc & 0x0F) << 12) + ((u2 & 0x3F) << 6) + (u3 & 0x3F);
          } else {
            if (head == tail) {
              break;
            }
            final int u4 = buf[head++];
            if ((bc & 0xF8) == 0xF0) {
              bc = ((bc & 0x07) << 18) + ((u2 & 0x3F) << 12) + ((u3 & 0x3F) << 6) + (u4 & 0x3F);
            } else {
              throw new IllegalStateException("invalid unicode character");
            }
            if (bc >= 0x10000) {
              // check if valid unicode
              if (bc >= 0x110000) {
                throw new IllegalStateException("invalid unicode character");
              }
              // split surrogates
              final int sup = bc - 0x10000;
              if (false) {
                
              }
              out[j++] = (char) ((sup >>> 10) + 0xd800);
              if (false) {
                
              }
              out[j++] = (char) ((sup & 0x3ff) + 0xdc00);
              continue;
            }
          }
        }
      }
      if (false) {
        
      }
      out[j++] = (char) bc;
    }
    throw new IllegalStateException("incomplete string");
  }


  private static int decodeHex(final int b) {
    final int digit = Character.digit(b, 16);
    if (digit < 0) {
      throw new IllegalStateException("invalid hex: " + b);
    }
    return digit;
  }

  private MultiByteDecodeKernels() {
  }
}
