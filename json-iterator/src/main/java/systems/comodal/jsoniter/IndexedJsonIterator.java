package systems.comodal.jsoniter;

/// A [JsonIterator] accelerated by a simdjson-style structural index
/// ([StructuralIndex]): stage 1 scans the entire document with the Vector API
/// and records the position of every structural token, and navigation then
/// hops between those tokens.
///
/// - Skipping any value — however large or deeply nested — touches only
///   structural tokens; string content, escaped quotes, and brackets inside
///   strings are never rescanned.
/// - Unclosed strings are detected during indexing, so `parse`/`index`/`reset`
///   throw a [JsonException] for truncated documents up front.
/// - Escape *sequences* are not validated when skipped, only when read.
///
/// The index is built over the caller's buffer without copying, and index
/// storage is reused across `reset` calls.
///
/// Obtain one directly via the static `parse` methods, or from any existing
/// iterator via [JsonIterator#index()], which indexes from the iterator's
/// current position — useful for on-demand navigation of a sub-document.
public interface IndexedJsonIterator extends JsonIterator {

  static IndexedJsonIterator parse(final byte[] json) {
    return new IndexedBytesJsonIterator(json, 0, json.length, false);
  }

  static IndexedJsonIterator parse(final byte[] json, final int head, final int tail) {
    return new IndexedBytesJsonIterator(json, head, tail, false);
  }

  static IndexedJsonIterator parse(final char[] json) {
    return new IndexedCharsJsonIterator(json, 0, json.length);
  }

  static IndexedJsonIterator parse(final char[] json, final int head, final int tail) {
    return new IndexedCharsJsonIterator(json, head, tail);
  }

  static IndexedJsonIterator parse(final String json) {
    return parse(json.getBytes());
  }

  /// Like [#parse(byte[])], but the document is first checked to be valid
  /// UTF-8 with a vectorized validator ([Utf8Validator]); a [JsonException] is
  /// thrown otherwise. The check re-applies to any document later passed to
  /// the returned iterator's `reset` methods.
  static IndexedJsonIterator parseValidating(final byte[] json) {
    return new IndexedBytesJsonIterator(json, 0, json.length, true);
  }

  static IndexedJsonIterator parseValidating(final byte[] json, final int head, final int tail) {
    return new IndexedBytesJsonIterator(json, head, tail, true);
  }

  /// Like [JsonIterator#skipUntil(String)], but order-independent, after
  /// simdjson's find_field_unordered: if the field is not found between the
  /// cursor and the end of the enclosing object, the search wraps to the
  /// object's start and covers the fields before the entry point. Returns
  /// null and restores the cursor when the field is absent entirely.
  ///
  /// Fields can therefore be extracted in any order without manual mark/reset
  /// bookkeeping, paying one extra object scan only when wrapping.
  JsonIterator findField(final String field);

  /// Already indexed; returns this iterator.
  @Override
  IndexedJsonIterator index();
}
