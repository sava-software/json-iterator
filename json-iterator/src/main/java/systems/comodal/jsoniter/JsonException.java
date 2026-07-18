package systems.comodal.jsoniter;

public class JsonException extends RuntimeException {

  private final String op;
  private final int offset;
  private final String context;

  public JsonException() {
    this.op = null;
    this.offset = -1;
    this.context = null;
  }

  public JsonException(final String message) {
    super(message);
    this.op = null;
    this.offset = -1;
    this.context = null;
  }

  public JsonException(final String message, final Throwable cause) {
    super(message, cause);
    this.op = null;
    this.offset = -1;
    this.context = null;
  }

  public JsonException(final Throwable cause) {
    super(cause);
    this.op = null;
    this.offset = -1;
    this.context = null;
  }

  JsonException(final String op, final int offset, final String context, final String message) {
    super(message);
    this.op = op;
    this.offset = offset;
    this.context = context;
  }

  /// The iterator operation that failed, e.g. `readString`, or null when this
  /// exception did not originate from an iterator parse failure.
  public final String op() {
    return op;
  }

  /// The buffer position (byte or char offset, matching the iterator's
  /// source) where the failure was detected, or -1 when unknown.
  public final int offset() {
    return offset;
  }

  /// Excerpt of the JSON around the failure with `»` marking the parse
  /// position — the offending input is adjacent to the marker: before it
  /// when the failing operation consumed the bad token, after it when it
  /// peeked. Bounded by [BaseJsonIterator#ERROR_CONTEXT_RADIUS] on each
  /// side; `…` marks a truncated end. Null when this exception did not
  /// originate from an iterator parse failure.
  public final String context() {
    return context;
  }
}
