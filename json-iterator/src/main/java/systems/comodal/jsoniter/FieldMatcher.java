package systems.comodal.jsoniter;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.TreeSet;
import java.util.function.Function;

/// Immutable set of expected field names compiled once into a hash table,
/// mapping a field name span to its index in the declared order — O(1)
/// dispatch regardless of how many fields are declared, in place of a
/// sequential fieldEquals chain.
///
/// Use with [JsonIterator#testObject(FieldMatcher, FieldIndexPredicate)] and
/// switch on the index:
///
/// ```java
/// private static final FieldMatcher FIELDS = FieldMatcher.of("fee", "computeUnitsConsumed");
/// ji.testObject(FIELDS, (fieldIndex, jsonIterator) -> {
///   switch (fieldIndex) {
///     case 0 -> fees += jsonIterator.readLong();
///     case 1 -> computeUnits += jsonIterator.readLong();
///     default -> jsonIterator.skip();
///   }
///   return true;
/// });
/// ```
public final class FieldMatcher {

  // Big-endian so a single bounds-checked read produces the same word the
  // byte-at-a-time loops build: first byte in the most significant position.
  private static final VarHandle TO_LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

  private final byte[][] names;
  private final int[] table;

  private FieldMatcher(final byte[][] names, final int[] table) {
    this.names = names;
    this.table = table;
  }

  public static FieldMatcher of(final String... fields) {
    final byte[][] names = new byte[fields.length][];
    for (int i = 0; i < fields.length; ++i) {
      names[i] = fields[i].getBytes(StandardCharsets.UTF_8);
    }
    // Low load factor: expected lookups probe once and unknown names land on
    // an empty slot without a verifying comparison.
    int capacity = Integer.highestOneBit(Math.max(names.length << 2, 4));
    if (capacity < names.length << 2) {
      capacity <<= 1;
    }
    final int[] table = new int[capacity];
    Arrays.fill(table, -1);
    final int mask = capacity - 1;
    for (int i = 0; i < names.length; ++i) {
      final byte[] name = names[i];
      int slot = slot(hash(name, 0, name.length), mask);
      for (int idx; (idx = table[slot]) >= 0; slot = (slot + 1) & mask) {
        if (Arrays.equals(names[idx], name)) {
          throw new IllegalArgumentException("duplicate field name: " + fields[i]);
        }
      }
      table[slot] = i;
    }
    return new FieldMatcher(names, table);
  }

  /// Builds a value matcher for an enum: resolves a string value span to the
  /// enum constant whose name matches, or `null` for an unknown value. Use
  /// with [JsonIterator#applyChars(CharBufferFunction)]:
  ///
  /// ```java
  /// private static final CharBufferFunction<Mode> PARSER = FieldMatcher.enumMatcher(Mode.values());
  /// ...
  /// mode = ji.applyChars(PARSER);
  /// ```
  ///
  /// Matching is exact (case-sensitive); wire values with case variance
  /// should stay on a [JsonIterator#fieldEqualsIgnoreCase] chain.
  public static <E extends Enum<E>> CharBufferFunction<E> enumMatcher(final E[] values) {
    return enumMatcher(values, Enum::name);
  }

  /// [#enumMatcher(Enum[])] with wire names decoupled from the constant
  /// names, e.g. `enumMatcher(Scope.values(), Scope::wireName)`. Duplicate
  /// wire names throw [IllegalArgumentException].
  public static <E extends Enum<E>> CharBufferFunction<E> enumMatcher(final E[] values,
                                                                      final Function<E, String> wireName) {
    final var names = new String[values.length];
    for (int i = 0; i < values.length; ++i) {
      names[i] = wireName.apply(values[i]);
    }
    final var matcher = of(names);
    return (buf, offset, len) -> {
      final int i = matcher.match(buf, offset, len);
      return i < 0 ? null : values[i];
    };
  }

  /// Case-insensitive [#enumMatcher(Enum[])] for wire values with case
  /// variance (e.g. `"Anchor"` vs `"anchor"`). Resolves by linear
  /// [JsonIterator#fieldEqualsIgnoreCase] scan rather than the hash table —
  /// suited to the small constant counts of enums. Wire names that collide
  /// case-insensitively throw [IllegalArgumentException].
  public static <E extends Enum<E>> CharBufferFunction<E> enumMatcherIgnoreCase(final E[] values) {
    return enumMatcherIgnoreCase(values, Enum::name);
  }

  /// [#enumMatcherIgnoreCase(Enum[])] with wire names decoupled from the
  /// constant names.
  public static <E extends Enum<E>> CharBufferFunction<E> enumMatcherIgnoreCase(final E[] values,
                                                                                final Function<E, String> wireName) {
    final var names = new String[values.length];
    final var distinct = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    for (int i = 0; i < values.length; ++i) {
      names[i] = wireName.apply(values[i]);
      if (!distinct.add(names[i])) {
        throw new IllegalArgumentException("case-insensitive duplicate wire name: " + names[i]);
      }
    }
    return (buf, offset, len) -> {
      for (int i = 0; i < names.length; ++i) {
        if (JsonIterator.fieldEqualsIgnoreCase(names[i], buf, offset, len)) {
          return values[i];
        }
      }
      return null;
    };
  }

  /// Extends a base matcher with additional names: the base names keep their
  /// indices 0..(base.numFields() - 1) and the additional names follow, so a
  /// subclass dispatcher can delegate unhandled base indices to its parent
  /// without remapping.
  public static FieldMatcher of(final FieldMatcher base, final String... additional) {
    final byte[][] baseNames = base.names;
    final var fields = new String[baseNames.length + additional.length];
    for (int i = 0; i < baseNames.length; ++i) {
      fields[i] = new String(baseNames[i], StandardCharsets.UTF_8);
    }
    System.arraycopy(additional, 0, fields, baseNames.length, additional.length);
    return of(fields);
  }

  /// Returns the declared index of the name at `buf[offset, offset + len)`,
  /// or -1 if it is not one of the declared names.
  ///
  /// Public so value parsers can resolve string values inside an existing
  /// buffer callback while retaining span access for unknown-value fallbacks,
  /// e.g. within a [CharBufferFunction] passed to [JsonIterator#applyChars].
  public int match(final byte[] buf, final int offset, final int len) {
    final int mask = table.length - 1;
    int slot = slot(hash(buf, offset, len), mask);
    for (int idx; (idx = table[slot]) >= 0; slot = (slot + 1) & mask) {
      final byte[] name = names[idx];
      if (name.length == len && Arrays.equals(name, 0, len, buf, offset, offset + len)) {
        return idx;
      }
    }
    return -1;
  }

  /// Char-buffer counterpart to [#match(byte[], int, int)]: an ascii span
  /// hashes identically to its UTF-8 bytes with no copy. A span containing a
  /// non-ascii char must be matched through its UTF-8 encoding — char
  /// narrowing is not injective (e.g. "Ã©" would otherwise collide with "é").
  public int match(final char[] buf, final int offset, final int len) {
    int acc = 0;
    for (int i = offset, to = offset + len; i < to; ++i) {
      acc |= buf[i];
    }
    if (acc > 0x7F) {
      final byte[] utf8 = new String(buf, offset, len).getBytes(StandardCharsets.UTF_8);
      return match(utf8, 0, utf8.length);
    }
    final int mask = table.length - 1;
    int slot = slot(hash(buf, offset, len), mask);
    for (int idx; (idx = table[slot]) >= 0; slot = (slot + 1) & mask) {
      if (equals(names[idx], buf, offset, len)) {
        return idx;
      }
    }
    return -1;
  }

  /// Only reached with all-ascii spans: a name byte >= 0x80 can never equal
  /// an ascii char, so comparing UTF-8 names positionally stays exact.
  private static boolean equals(final byte[] name, final char[] buf, final int offset, final int len) {
    if (name.length != len) {
      return false;
    }
    for (int i = 0, j = offset; i < len; ++i, ++j) {
      if ((char) (name[i] & 0xff) != buf[j]) {
        return false;
      }
    }
    return true;
  }

  int numFields() {
    return names.length;
  }

  String name(final int fieldIndex) {
    return new String(names[fieldIndex], StandardCharsets.UTF_8);
  }

  private static int slot(final long hash, final int mask) {
    return Long.hashCode(hash) & mask;
  }

  /// Hashes the length plus the first and last (up to) eight bytes. Names
  /// identical in all three only pay an extra verifying comparison. Spans of
  /// eight bytes or more use two word loads; the caller guarantees
  /// `offset + len <= buf.length`.
  private static long hash(final byte[] buf, final int offset, final int len) {
    if (len >= Long.BYTES) {
      final long first = (long) TO_LONG.get(buf, offset);
      final long last = len > Long.BYTES
          ? (long) TO_LONG.get(buf, (offset + len) - Long.BYTES)
          : 0;
      return mix(first, last, len);
    }
    return mix(word(buf, offset, len), 0, len);
  }

  private static long hash(final char[] buf, final int offset, final int len) {
    final long first = word(buf, offset, Math.min(len, Long.BYTES));
    final long last = len > Long.BYTES
        ? word(buf, (offset + len) - Long.BYTES, Long.BYTES)
        : 0;
    return mix(first, last, len);
  }

  private static long mix(final long first, final long last, final int len) {
    long h = (first ^ len) * 0x9E3779B97F4A7C15L;
    h = (h ^ (h >>> 32) ^ last) * 0x9E3779B97F4A7C15L;
    return h;
  }

  private static long word(final byte[] buf, final int offset, final int count) {
    long word = 0;
    for (int i = 0; i < count; ++i) {
      word = (word << 8) | (buf[offset + i] & 0xff);
    }
    return word;
  }

  private static long word(final char[] buf, final int offset, final int count) {
    long word = 0;
    for (int i = 0; i < count; ++i) {
      word = (word << 8) | (buf[offset + i] & 0xff);
    }
    return word;
  }
}
