package systems.comodal.jsoniter.jmh;

import jsoniter.published.CharBufferToIntFunction;
import jsoniter.published.CharBufferToLongFunction;
import jsoniter.published.ContextFieldBufferPredicate;
import jsoniter.published.JsonIterator;
import systems.comodal.jsoniter.JIUtil;

/// [Walks#walk(systems.comodal.jsoniter.JsonIterator)] duplicated against the
/// relocated published 25.1.0 (published) baseline (package jsoniter.published). The checksum
/// lambdas share the same bodies — including the current [JIUtil#parseDouble]
/// helper — so the comparison isolates the iterator, not the user code.
final class WalksPublished {

  private static final CharBufferToIntFunction LENGTH = (buf, offset, len) -> len;

  private static final CharBufferToLongFunction NUMBER_CHECKSUM = (buf, offset, len) -> {
    for (int i = offset, to = offset + len; i < to; ++i) {
      final char c = buf[i];
      if (c == '.' || c == 'e' || c == 'E') {
        return (long) JIUtil.parseDouble(buf, offset, len);
      }
    }
    int i = offset;
    final boolean negative = buf[i] == '-';
    if (negative) {
      ++i;
    }
    long value = 0;
    for (final int to = offset + len; i < to; ++i) {
      value = value * 10 + (buf[i] - '0');
    }
    return negative ? -value : value;
  };

  private static final ContextFieldBufferPredicate<long[]> FIELD_WALKER = (sum, buf, offset, len, ji) -> {
    sum[0] += len + walk(ji);
    return true;
  };

  private WalksPublished() {
  }

  static long walk(final JsonIterator ji) {
    return switch (ji.whatIsNext()) {
      case OBJECT -> {
        final long[] sum = new long[1];
        ji.testObject(sum, FIELD_WALKER);
        yield sum[0];
      }
      case ARRAY -> {
        long sum = 0;
        while (ji.readArray()) {
          sum += walk(ji);
        }
        yield sum;
      }
      case STRING -> ji.applyCharsAsInt(LENGTH);
      case NUMBER -> ji.applyNumberCharsAsLong(NUMBER_CHECKSUM);
      case BOOLEAN -> ji.readBoolean() ? 1 : 0;
      case NULL -> {
        ji.skip();
        yield 1;
      }
      default -> throw new IllegalStateException("unexpected value type");
    };
  }
}
