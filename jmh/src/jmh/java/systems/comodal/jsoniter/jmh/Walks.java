package systems.comodal.jsoniter.jmh;

import org.simdjson.JsonValue;
import systems.comodal.jsoniter.CharBufferToIntFunction;
import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

/// Shared exhaustive-walk workloads: touch every field name and value,
/// folding them into a checksum that must agree across parsers.
///
/// The JsonIterator walk uses the inversion-of-control API the way real
/// parsers built on this library do (see sava's response parsers): field
/// names and string values are consumed as char buffers via
/// testObject/applyCharsAsInt and never materialized as Strings.
final class Walks {

  private static final CharBufferToIntFunction LENGTH = (buf, offset, len) -> len;

  private static final ContextFieldBufferPredicate<long[]> FIELD_WALKER = (sum, buf, offset, len, ji) -> {
    sum[0] += len + walk(ji);
    return true;
  };

  private Walks() {
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
      case NUMBER -> number(ji.readNumberAsString());
      case BOOLEAN -> ji.readBoolean() ? 1 : 0;
      case NULL -> {
        ji.skip();
        yield 1;
      }
      default -> throw new IllegalStateException(ji.currentBuffer());
    };
  }

  static long walk(final JsonValue value) {
    if (value.isObject()) {
      long sum = 0;
      for (final var it = value.objectIterator(); it.hasNext(); ) {
        final var field = it.next();
        sum += field.getKey().length() + walk(field.getValue());
      }
      return sum;
    } else if (value.isArray()) {
      long sum = 0;
      for (final var it = value.arrayIterator(); it.hasNext(); ) {
        sum += walk(it.next());
      }
      return sum;
    } else if (value.isString()) {
      return value.asString().length();
    } else if (value.isLong()) {
      return value.asLong();
    } else if (value.isDouble()) {
      return (long) value.asDouble();
    } else if (value.isBoolean()) {
      return value.asBoolean() ? 1 : 0;
    } else {
      return 1; // null
    }
  }

  static long number(final String number) {
    for (int i = 0; i < number.length(); ++i) {
      final char c = number.charAt(i);
      if (c == '.' || c == 'e' || c == 'E') {
        return (long) Double.parseDouble(number);
      }
    }
    return Long.parseLong(number);
  }
}
