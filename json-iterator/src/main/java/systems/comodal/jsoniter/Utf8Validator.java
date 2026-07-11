package systems.comodal.jsoniter;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorShuffle;

import java.util.Arrays;

import static jdk.incubator.vector.VectorOperators.*;
import static systems.comodal.jsoniter.VectorSupport.*;

/// Vectorized UTF-8 validation using the lookup algorithm from Keiser &
/// Lemire, [Validating UTF-8 In Less Than One Instruction Per Byte](https://arxiv.org/abs/2010.03090),
/// adapted from simdjson-java's port but generic over vector width (128/256/512-bit).
/// Each byte is classified by three 16-entry nibble lookups — the previous
/// byte's high and low nibbles and the current byte's high nibble — whose
/// intersection flags every 1-2 byte error class (overlong encodings,
/// surrogates, out-of-range code points, stray continuations). 3 and 4-byte
/// sequences are then cross-checked by comparing bytes two and three back
/// against the minimum leading byte values.
///
/// Stateful, one instance per iterator: [#check(ByteVector)] is invoked by
/// [StructuralIndex] on each chunk it has already loaded, fusing validation
/// into stage 1 in a single pass, as simdjson's json_structural_indexer does.
/// The indexer's space-padded final block terminates any trailing incomplete
/// sequence, which [#finish()] then reports via the incomplete accumulator.
final class Utf8Validator {

  // The leading byte isn't followed by a continuation byte, e.g. 11______ 0_______.
  private static final byte TOO_SHORT = 1;
  // ASCII followed by a continuation byte, e.g. 01111111 10_000000.
  private static final byte TOO_LONG = 1 << 1;
  // Any 3-byte sequence that fits a shorter encoding (below 1110_0000 10_100000 10_000000).
  private static final byte OVERLONG_3BYTE = 1 << 2;
  // Any decoded code point greater than U+10FFFF, e.g. 11110_100 10_010000 10_000000 10_000000.
  private static final byte TOO_LARGE = 1 << 3;
  // Code points in the range U+D800 - U+DFFF (UTF-16 surrogates) are disallowed in UTF-8.
  private static final byte SURROGATE = 1 << 4;
  // First valid 2-byte sequence: 110_00010 10_000000. Anything smaller fits one byte.
  private static final byte OVERLONG_2BYTE = 1 << 5;
  // Like TOO_LARGE, for continuation bytes whose high nibble is 1000.
  private static final byte TOO_LARGE_1000 = 1 << 6;
  // 4-byte sequences that fit a 3-byte encoding, e.g. 11110_000 10_000000 ...
  private static final byte OVERLONG_4BYTE = 1 << 6;
  // Two continuation bytes with no lead, e.g. 10_000000 10_000000.
  private static final byte TWO_CONTINUATIONS = (byte) (1 << 7);
  private static final byte MAX_2_LEADING_BYTE = (byte) 0b110_11111;
  private static final byte MAX_3_LEADING_BYTE = (byte) 0b1110_1111;
  private static final int TWO_BYTES_SIZE = Byte.SIZE * 2;
  private static final int THREE_BYTES_SIZE = Byte.SIZE * 3;
  private static final byte LOW_NIBBLE_MASK = 0b0000_1111;
  private static final byte ALL_ASCII_MASK = (byte) 0b1000_0000;
  private static final ByteVector BYTE_1_HIGH_LOOKUP = createByte1HighLookup();
  private static final ByteVector BYTE_1_LOW_LOOKUP = createByte1LowLookup();
  private static final ByteVector BYTE_2_HIGH_LOOKUP = createByte2HighLookup();
  private static final ByteVector INCOMPLETE_CHECK = createIncompleteCheck();
  private static final VectorShuffle<Integer> FOUR_BYTES_FORWARD_SHIFT = VectorShuffle.iota(INT_SPECIES, INT_SPECIES.length() - 1, 1, true);

  private long incomplete;
  private long errors;
  private int previousFourUtf8Bytes;

  Utf8Validator() {
  }

  void reset() {
    incomplete = 0;
    errors = 0;
    previousFourUtf8Bytes = 0;
  }

  void check(final ByteVector chunk) {
    final var chunkAsInts = chunk.reinterpretAsInts();
    // The ASCII fast path bypasses the multibyte classification.
    if (chunk.and(ALL_ASCII_MASK).compare(EQ, 0).allTrue()) {
      errors |= incomplete;
    } else {
      incomplete = chunk.compare(UGE, INCOMPLETE_CHECK).toLong();
      // Rotate the int lanes forward by one and pull in the previous chunk's
      // last int, giving access to the previous four bytes via int shifts,
      // which are cheaper than byte-wise slice/shuffle operations.
      final var chunkWithPreviousFourBytes = chunkAsInts
          .rearrange(FOUR_BYTES_FORWARD_SHIFT)
          .withLane(0, previousFourUtf8Bytes);
      final var previousOneByte = chunkAsInts
          .lanewise(LSHL, Byte.SIZE)
          .or(chunkWithPreviousFourBytes.lanewise(LSHR, THREE_BYTES_SIZE))
          .reinterpretAsBytes();
      final var byte2HighNibbles = chunkAsInts.lanewise(LSHR, 4)
          .reinterpretAsBytes()
          .and(LOW_NIBBLE_MASK);
      final var byte1HighNibbles = previousOneByte.reinterpretAsInts()
          .lanewise(LSHR, 4)
          .reinterpretAsBytes()
          .and(LOW_NIBBLE_MASK);
      final var byte1LowNibbles = previousOneByte.and(LOW_NIBBLE_MASK);
      final var firstCheck = byte1HighNibbles.selectFrom(BYTE_1_HIGH_LOOKUP)
          .and(byte1LowNibbles.selectFrom(BYTE_1_LOW_LOOKUP))
          .and(byte2HighNibbles.selectFrom(BYTE_2_HIGH_LOOKUP));
      // The remaining checks validate 3 and 4-byte sequences: firstCheck holds
      // 0x80 at continuation positions, which the leading bytes of 3 and
      // 4-byte sequences must zero out.
      final var previousTwoBytes = chunkAsInts
          .lanewise(LSHL, TWO_BYTES_SIZE)
          .or(chunkWithPreviousFourBytes.lanewise(LSHR, TWO_BYTES_SIZE))
          .reinterpretAsBytes();
      final var is3ByteLead = previousTwoBytes.compare(UGT, MAX_2_LEADING_BYTE);
      final var previousThreeBytes = chunkAsInts
          .lanewise(LSHL, THREE_BYTES_SIZE)
          .or(chunkWithPreviousFourBytes.lanewise(LSHR, Byte.SIZE))
          .reinterpretAsBytes();
      final var is4ByteLead = previousThreeBytes.compare(UGT, MAX_3_LEADING_BYTE);
      final var secondCheck = firstCheck.add((byte) 0x80, is3ByteLead.or(is4ByteLead));
      errors |= secondCheck.compare(NE, 0).toLong();
    }
    previousFourUtf8Bytes = chunkAsInts.lane(INT_SPECIES.length() - 1);
  }

  void finish() {
    if ((errors | incomplete) != 0) {
      throw new JsonException("Invalid UTF-8 input.");
    }
  }

  private static ByteVector createIncompleteCheck() {
    // The previous chunk ends incompletely if its last byte is >= 0xC0,
    // its second to last is >= 0xE0, or its third to last is >= 0xF0.
    final byte[] eofArray = new byte[BYTE_LANES];
    Arrays.fill(eofArray, (byte) 255);
    eofArray[BYTE_LANES - 3] = (byte) 0xF0;
    eofArray[BYTE_LANES - 2] = (byte) 0xE0;
    eofArray[BYTE_LANES - 1] = (byte) 0xC0;
    return ByteVector.fromArray(BYTE_SPECIES, eofArray, 0);
  }

  private static ByteVector createByte1HighLookup() {
    return alignArrayToVector(new byte[]{
        // ASCII high nibble = 0000 -> 0111
        TOO_LONG, TOO_LONG, TOO_LONG, TOO_LONG, TOO_LONG, TOO_LONG, TOO_LONG, TOO_LONG,
        // continuation high nibble = 1000 -> 1011
        TWO_CONTINUATIONS, TWO_CONTINUATIONS, TWO_CONTINUATIONS, TWO_CONTINUATIONS,
        // 2-byte lead high nibble = 1100 -> 1101
        (byte) (TOO_SHORT | OVERLONG_2BYTE), TOO_SHORT,
        // 3-byte lead high nibble = 1110
        (byte) (TOO_SHORT | OVERLONG_3BYTE | SURROGATE),
        // 4-byte lead high nibble = 1111
        (byte) (TOO_SHORT | TOO_LARGE | TOO_LARGE_1000 | OVERLONG_4BYTE)
    });
  }

  private static ByteVector createByte1LowLookup() {
    final byte carry = TOO_SHORT | TOO_LONG | TWO_CONTINUATIONS;
    return alignArrayToVector(new byte[]{
        (byte) (carry | OVERLONG_2BYTE | OVERLONG_3BYTE | OVERLONG_4BYTE),
        (byte) (carry | OVERLONG_2BYTE),
        carry,
        carry,
        // 1111_0100 -> 1111 = TOO_LARGE range
        (byte) (carry | TOO_LARGE),
        (byte) (carry | TOO_LARGE | TOO_LARGE_1000),
        (byte) (carry | TOO_LARGE | TOO_LARGE_1000),
        (byte) (carry | TOO_LARGE | TOO_LARGE_1000),
        (byte) (carry | TOO_LARGE | TOO_LARGE_1000),
        (byte) (carry | TOO_LARGE | TOO_LARGE_1000),
        (byte) (carry | TOO_LARGE | TOO_LARGE_1000),
        (byte) (carry | TOO_LARGE | TOO_LARGE_1000),
        (byte) (carry | TOO_LARGE | TOO_LARGE_1000),
        // 1110_1101
        (byte) (carry | TOO_LARGE | TOO_LARGE_1000 | SURROGATE),
        (byte) (carry | TOO_LARGE | TOO_LARGE_1000),
        (byte) (carry | TOO_LARGE | TOO_LARGE_1000)
    });
  }

  private static ByteVector createByte2HighLookup() {
    return alignArrayToVector(new byte[]{
        // ASCII high nibble = 0000 -> 0111
        TOO_SHORT, TOO_SHORT, TOO_SHORT, TOO_SHORT, TOO_SHORT, TOO_SHORT, TOO_SHORT, TOO_SHORT,
        // continuation high nibble = 1000 -> 1011
        (byte) (TOO_LONG | TWO_CONTINUATIONS | OVERLONG_2BYTE | OVERLONG_3BYTE | OVERLONG_4BYTE | TOO_LARGE_1000),
        (byte) (TOO_LONG | TWO_CONTINUATIONS | OVERLONG_2BYTE | OVERLONG_3BYTE | TOO_LARGE),
        (byte) (TOO_LONG | TWO_CONTINUATIONS | OVERLONG_2BYTE | SURROGATE | TOO_LARGE),
        (byte) (TOO_LONG | TWO_CONTINUATIONS | OVERLONG_2BYTE | SURROGATE | TOO_LARGE),
        // 1100 -> 1111 = unexpected leading byte
        TOO_SHORT, TOO_SHORT, TOO_SHORT, TOO_SHORT
    });
  }

  private static ByteVector alignArrayToVector(final byte[] values) {
    // Pad with zeroes up to the vector length; selectFrom indexes are nibbles (< 16).
    final byte[] aligned = new byte[BYTE_LANES];
    System.arraycopy(values, 0, aligned, 0, values.length);
    return ByteVector.fromArray(BYTE_SPECIES, aligned, 0);
  }
}
