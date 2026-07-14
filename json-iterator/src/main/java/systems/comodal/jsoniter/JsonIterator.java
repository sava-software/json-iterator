package systems.comodal.jsoniter;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

public interface JsonIterator {

  static JsonIterator parse(final byte[] buf) {
    return new BytesJsonIterator(buf, 0, buf.length);
  }

  static JsonIterator parse(final byte[] buf, final int charBufferLength) {
    return new BytesJsonIterator(buf, 0, buf.length, charBufferLength);
  }

  static JsonIterator parse(final byte[] buf, final int head, final int tail) {
    return new BytesJsonIterator(buf, head, tail);
  }

  static JsonIterator parse(final byte[] buf, final int head, final int tail, final int charBufferLength) {
    return new BytesJsonIterator(buf, head, tail, charBufferLength);
  }

  static JsonIterator parse(final char[] buf) {
    return new CharsJsonIterator(buf, 0, buf.length);
  }

  static JsonIterator parse(final char[] buf, final int head, final int tail) {
    return new CharsJsonIterator(buf, head, tail);
  }

  static JsonIterator parse(final String field) {
    return parse(field.getBytes());
  }

  static JsonIterator parse(final String field, final int charBufferLength) {
    return parse(field.getBytes(), charBufferLength);
  }

  /// Reads the stream to EOF, closes it, and iterates over the resulting `byte[]`.
  private static byte[] readFully(final InputStream in) {
    try (in) {
      return in.readAllBytes();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static JsonIterator parse(final InputStream in) {
    return parse(readFully(in));
  }

  /// @param bufSize ignored; the stream is always read fully.
  @Deprecated(forRemoval = true)
  static JsonIterator parse(final InputStream in, final int bufSize) {
    return parse(readFully(in));
  }

  /// @param bufSize ignored; the stream is always read fully.
  @Deprecated(forRemoval = true)
  static JsonIterator parse(final InputStream in, final int bufSize, final int charBufferLength) {
    return parse(readFully(in), charBufferLength);
  }

  static boolean fieldEquals(final String field, final char[] buf) {
    return fieldEquals(field, buf, 0, buf.length);
  }

  static boolean fieldEquals(final String field, final char[] buf, final int len) {
    return fieldEquals(field, buf, 0, len);
  }

  static boolean fieldEquals(final String field, final char[] buf, final int offset, final int len) {
    if (field.length() != len) {
      return false;
    }
    for (int i = 0, j = offset; i < len; i++, j++) {
      if (field.charAt(i) != buf[j]) {
        return false;
      }
    }
    return true;
  }

  static boolean fieldStartsWith(final String field, final char[] buf, final int offset, final int len) {
    final int to = field.length();
    if (to > len) {
      return false;
    }
    for (int i = 0, j = offset; i < to; i++, j++) {
      if (field.charAt(i) != buf[j]) {
        return false;
      }
    }
    return true;
  }

  static boolean fieldEqualsIgnoreCase(final String field, final char[] buf) {
    return fieldEqualsIgnoreCase(field, buf, 0, buf.length);
  }

  static boolean fieldEqualsIgnoreCase(final String field, final char[] buf, final int len) {
    return fieldEqualsIgnoreCase(field, buf, 0, len);
  }

  static boolean fieldEqualsIgnoreCase(final String field, final char[] buf, final int offset, final int len) {
    if (field.length() != len) {
      return false;
    }
    for (int i = 0, j = offset, c, d; i < len; i++, j++) {
      c = field.charAt(i);
      d = buf[j];
      if (c != d
          && Character.toLowerCase(c) != d
          && Character.toUpperCase(c) != d) {
        return false;
      }
    }
    return true;
  }

  static boolean fieldStartsWithIgnoreCase(final String field, final char[] buf, final int offset, final int len) {
    final int to = field.length();
    if (to > len) {
      return false;
    }
    for (int i = 0, j = offset, c, d; i < to; i++, j++) {
      c = field.charAt(i);
      d = buf[j];
      if (c != d
          && Character.toLowerCase(c) != d
          && Character.toUpperCase(c) != d) {
        return false;
      }
    }
    return true;
  }

  static boolean fieldEndsWith(final String field, final char[] buf, final int offset, final int len) {
    int i = field.length();
    if (i > len) {
      return false;
    }
    for (int j = offset + len; --i >= 0; ) {
      if (field.charAt(i) != buf[--j]) {
        return false;
      }
    }
    return true;
  }

  static boolean fieldEndsWithIgnoreCase(final String field, final char[] buf, final int offset, final int len) {
    int i = field.length();
    if (i > len) {
      return false;
    }
    for (int j = offset + len, c, d; --i >= 0; ) {
      c = field.charAt(i);
      d = buf[--j];
      if (c != d
          && Character.toLowerCase(c) != d
          && Character.toUpperCase(c) != d) {
        return false;
      }
    }
    return true;
  }

  JsonIterator reset(final int mark);

  JsonIterator reset(final byte[] buf);

  JsonIterator reset(final byte[] buf, final int head, final int tail);

  JsonIterator reset(final char[] buf);

  JsonIterator reset(final char[] buf, final int head, final int tail);

  /// Reads the stream to EOF, closes it, and iterates over the resulting `byte[]`.
  JsonIterator reset(final InputStream in);

  /// @param bufSize ignored; the stream is always read fully.
  @Deprecated
  JsonIterator reset(final InputStream in, final int bufSize);

  String currentBuffer();

  // Object Field & Navigation Methods

  /// All iterators support mark/reset now that InputStream sources are read fully upfront.
  @Deprecated
  default boolean supportsMarkReset() {
    return true;
  }

  int mark();

  ValueType whatIsNext();

  boolean readArray();

  /// Reads each array element with `parser` and adds it to `collection`.
  /// A JSON `null` value reads as an empty array, consistent with
  /// [#readArray()]; callers that must distinguish `null` should guard with
  /// [#notNull()]:
  ///
  /// ```java
  /// this.routePlan = ji.notNull() ? ji.readList(JupiterRoute::parse) : null;
  /// ```
  default <T, C extends Collection<? super T>> C readCollection(final C collection,
                                                                final Function<JsonIterator, T> parser) {
    while (readArray()) {
      collection.add(parser.apply(this));
    }
    return collection;
  }

  /// Reads an array into a [List], one element per `parser` application, e.g.
  /// `ji.readList(JsonIterator::readString)`. A JSON `null` value reads as an
  /// empty list; see [#readCollection(Collection, Function)].
  default <T> List<T> readList(final Function<JsonIterator, T> parser) {
    return readCollection(new ArrayList<>(), parser);
  }

  /// Reads each object field as a map entry: the key is parsed from the field
  /// name span and passed to `valueParser` along with this iterator, so value
  /// factories that carry their key map directly, e.g.
  /// `ji.readMap(PARSE_BASE58_PUBLIC_KEY, JupiterPrice::parsePrice)`. For
  /// String keys, `String::new` is a [CharBufferFunction].
  ///
  /// A JSON `null` value reads as an empty map, consistent with [#readArray()]
  /// and [#testObject(Object, ContextFieldBufferPredicate)]; callers that must
  /// distinguish `null` should guard with [#notNull()]. Duplicate field names
  /// follow [Map#put] semantics: the last entry wins.
  default <K, V, M extends Map<? super K, ? super V>> M readMap(final M map,
                                                                final CharBufferFunction<K> keyParser,
                                                                final BiFunction<K, JsonIterator, V> valueParser) {
    return testObject(map, (m, buf, offset, len, ji) -> {
      final var key = keyParser.apply(buf, offset, len);
      m.put(key, valueParser.apply(key, ji));
      return true;
    });
  }

  /// [HashMap] convenience over
  /// [#readMap(Map, CharBufferFunction, BiFunction)].
  default <K, V> Map<K, V> readMap(final CharBufferFunction<K> keyParser,
                                   final BiFunction<K, JsonIterator, V> valueParser) {
    return readMap(new HashMap<>(), keyParser, valueParser);
  }

  /// Reads an array of values into a map, each keyed by `keyExtractor`
  /// applied to the parsed value, e.g.
  /// `ji.readMap(JupiterTokenV2::parseToken, JupiterTokenV2::address)`.
  ///
  /// A JSON `null` value reads as an empty map, consistent with
  /// [#readArray()]; callers that must distinguish `null` should guard with
  /// [#notNull()]. Duplicate keys follow [Map#put] semantics: the last
  /// element wins.
  default <K, V, M extends Map<? super K, ? super V>> M readMap(final M map,
                                                                final Function<JsonIterator, V> valueParser,
                                                                final Function<V, K> keyExtractor) {
    while (readArray()) {
      final var value = valueParser.apply(this);
      map.put(keyExtractor.apply(value), value);
    }
    return map;
  }

  /// [HashMap] convenience over [#readMap(Map, Function, Function)].
  default <K, V> Map<K, V> readMap(final Function<JsonIterator, V> valueParser,
                                   final Function<V, K> keyExtractor) {
    return readMap(new HashMap<>(), valueParser, keyExtractor);
  }

  JsonIterator openArray();

  JsonIterator continueArray();

  JsonIterator closeArray();

  /// @deprecated allocates a String per field name; iterate objects with the
  /// [#testObject(FieldMatcher, FieldIndexPredicate)] family instead.
  @Deprecated(forRemoval = true)
  default String readObject() {
    return readObjField();
  }

  /// @deprecated allocates a String per field name; iterate objects with the
  /// [#testObject(FieldMatcher, FieldIndexPredicate)] family instead.
  @Deprecated(forRemoval = true)
  String readObjField();

  JsonIterator skipObjField();

  JsonIterator skipUntil(final String field);

  JsonIterator closeObj();

  // Value Methods

  JsonIterator skip();

  default JsonIterator skipRestOfObject() {
    while (skipObjField() != null) {
      skip();
    }
    return this;
  }

  default JsonIterator skipRestOfArray() {
    while (readArray()) {
      skip();
    }
    return this;
  }

  /// Advances the iterator if the next item is `null` and returns true.
  /// Otherwise, stays in place and returns false.
  ///
  /// @return true if value was `null`.
  boolean readNull();

  /// Inverse of [#readNull()], for guarding a read against a `null` value:
  ///
  /// Advances the iterator if the next item is `null` and returns false.
  /// Otherwise, stays in place and returns true.
  ///
  /// @return true if a value other than `null` is next.
  default boolean notNull() {
    return !readNull();
  }

  String readString();

  byte[] decodeBase64String();

  String readNumberAsString();

  String readNumberOrNumberString();

  boolean readBoolean();

  short readShort();

  int readInt();

  long readLong();

  float readFloat();

  double readDouble();

  BigDecimal readBigDecimal();

  @Deprecated(forRemoval = true)
  default BigDecimal readBigDecimalStripTrailingZeroes() {
    return readBigDecimalDropZeroes();
  }

  /// Drops trailing decimal zeroes.
  BigDecimal readBigDecimalDropZeroes();

  long readUnscaledAsLong(final int scale);

  BigInteger readBigInteger();

  /// Parses ISO-like or RFC-1123 formats such as:
  /// - `YYYY*MM*DD*HH*MM*SS.?\d{0,9}Z?`
  /// - `YYYY*MM*DD*HH*MM*SS[+-]HH*MM`
  /// - `Fri, 04 Oct 2019 16:06:36 GMT`
  /// - `Fri, 04 Oct 2019 16:06:36 +0200`
  ///
  /// For the ISO-like forms, `*` may be any single separator character —
  /// separators are not validated — and UTC is assumed if no offset is
  /// provided. For the RFC-1123 form, the day must be two digits (unlike
  /// [java.time.format.DateTimeFormatter#RFC_1123_DATE_TIME], `04`, never
  /// `4`), the day-of-week prefix is not validated against the date, `GMT`
  /// is computed directly, and any other zone is resolved via
  /// [java.time.ZoneId#of(String)].
  ///
  /// Field values are used arithmetically without range validation.
  ///
  /// @return the parsed Instant
  /// @throws java.time.DateTimeException on any unexpected character or length
  Instant readDateTime();

  // IOC Field Value Methods

  /// Construct an Object of type R directly from the `char[]` representing the next String value.
  ///
  /// The function is not called for null values, instead null is directly returned.
  ///
  /// @param applyChars This array buffer is reused throughout the life of this iterator.
  /// @param <R>        Resulting Object Type.
  /// @return Object constructed from applyChars.
  <R> R applyChars(final CharBufferFunction<R> applyChars);

  <C, R> R applyChars(final C context, final ContextCharBufferFunction<C, R> applyChars);

  <R> R applyNumberChars(final CharBufferFunction<R> applyChars);

  <C, R> R applyNumberChars(final C context, final ContextCharBufferFunction<C, R> applyChars);

  int applyCharsAsInt(final CharBufferToIntFunction applyChars);

  <C> int applyCharsAsInt(final C context, final ContextCharBufferToIntFunction<C> applyChars);

  int applyNumberCharsAsInt(final CharBufferToIntFunction applyChars);

  <C> int applyNumberCharsAsInt(final C context, final ContextCharBufferToIntFunction<C> applyChars);

  long applyCharsAsLong(final CharBufferToLongFunction applyChars);

  <C> long applyCharsAsLong(final C context, final ContextCharBufferToLongFunction<C> applyChars);

  long applyNumberCharsAsLong(final CharBufferToLongFunction applyChars);

  <C> long applyNumberCharsAsLong(final C context, final ContextCharBufferToLongFunction<C> applyChars);

  boolean testChars(final CharBufferPredicate testChars);

  <C> boolean testChars(final C context, final ContextCharBufferPredicate<C> testChars);

  void consumeChars(final CharBufferConsumer testChars);

  <C> void consumeChars(final C context, final ContextCharBufferConsumer<C> testChars);

  // IOC Field Methods

  /// @deprecated single-field probes are covered by
  /// [#testObject(FieldMatcher, FieldIndexPredicate)]; break out after the
  /// first field.
  @Deprecated(forRemoval = true)
  boolean testObjField(final CharBufferPredicate testField);

  /// @deprecated single-field probes are covered by
  /// [#testObject(FieldMatcher, FieldIndexPredicate)]; break out after the
  /// first field.
  @Deprecated(forRemoval = true)
  <R> R applyObjField(final CharBufferFunction<R> applyChars);

  <C, R> R applyObject(final C context, final ContextFieldBufferFunction<C, R> fieldBufferFunction);

  /// @deprecated single-field probes are covered by
  /// [#testObject(FieldMatcher, FieldIndexPredicate)]; break out after the
  /// first field.
  @Deprecated(forRemoval = true)
  int applyObjFieldAsInt(final CharBufferToIntFunction applyChars, final int terminalSentinel);

  /// @deprecated single-field probes are covered by
  /// [#testObject(FieldMatcher, FieldIndexPredicate)]; break out after the
  /// first field.
  @Deprecated(forRemoval = true)
  long applyObjFieldAsLong(final CharBufferToLongFunction applyChars, final long terminalSentinel);

  /// @deprecated single-field probes are covered by
  /// [#testObject(FieldMatcher, FieldIndexPredicate)]; break out after the
  /// first field.
  @Deprecated(forRemoval = true)
  <C> int applyObjFieldAsInt(final C context,
                             final ContextCharBufferToIntFunction<C> applyChars,
                             final int terminalSentinel);

  /// @deprecated single-field probes are covered by
  /// [#testObject(FieldMatcher, FieldIndexPredicate)]; break out after the
  /// first field.
  @Deprecated(forRemoval = true)
  <C> long applyObjFieldAsLong(final C context,
                               final ContextCharBufferToLongFunction<C> applyChars,
                               final long terminalSentinel);

  <R> R applyObject(final FieldBufferFunction<R> fieldBufferFunction);

  <C> C testObject(final C context, final ContextFieldBufferPredicate<C> fieldBufferFunction);

  void testObject(final FieldBufferPredicate fieldBufferFunction);

  /// @deprecated unused by known consumers and superseded by
  /// [#testObject(Object, FieldMatcher, ContextFieldIndexMaskedPredicate)],
  /// whose int index makes the seen-fields mask trivial to manage.
  @Deprecated(forRemoval = true)
  <C> C testObject(final C context, final ContextFieldBufferMaskedPredicate<C> fieldBufferFunction);

  /// [FieldMatcher]-driven variants: each field name is resolved to its
  /// index in the matcher's declared order (-1 if unknown) with a single
  /// O(1) lookup, replacing sequential fieldEquals chains.
  void testObject(final FieldMatcher matcher, final FieldIndexPredicate fieldPredicate);

  <C> C testObject(final C context, final FieldMatcher matcher, final ContextFieldIndexPredicate<C> fieldPredicate);

  /// Masked matcher variant: the predicate threads a bitmask of seen field
  /// indices and returns [ContextFieldIndexMaskedPredicate#BREAK_OUT] to stop
  /// once every wanted field has been captured, skipping the rest of the
  /// object.
  <C> C testObject(final C context,
                   final FieldMatcher matcher,
                   final ContextFieldIndexMaskedPredicate<C> fieldPredicate);

  /// Reads the next string value and resolves it through the matcher on the
  /// same zero-copy fast path as field-name dispatch — for enum values and
  /// string discriminators. Returns -1 for an unrecognized value or JSON
  /// null; callers needing the unrecognized text should instead resolve via
  /// [FieldMatcher#match] inside an [#applyChars] callback.
  int matchString(final FieldMatcher matcher);
}
