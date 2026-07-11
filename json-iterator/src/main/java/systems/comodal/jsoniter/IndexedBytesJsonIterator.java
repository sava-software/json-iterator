package systems.comodal.jsoniter;

final class IndexedBytesJsonIterator extends BytesJsonIterator implements IndexedJsonIterator {

  private final StructuralIndex index;
  private final Utf8Validator utf8;
  private int[] tokens;
  private int pos;

  IndexedBytesJsonIterator(final byte[] buf, final int head, final int tail, final boolean validateUtf8) {
    super(buf, head, tail);
    this.index = new StructuralIndex();
    this.utf8 = validateUtf8 ? new Utf8Validator() : null;
    index(buf, head, tail);
  }

  private void index(final byte[] buf, final int from, final int to) {
    index.index(buf, from, to, utf8);
    this.tokens = index.indexes();
    this.pos = 0;
  }

  @Override
  public IndexedJsonIterator index() {
    return this;
  }

  @Override
  public JsonIterator reset(final byte[] buf) {
    super.reset(buf);
    index(buf, 0, buf.length);
    return this;
  }

  @Override
  public JsonIterator reset(final byte[] buf, final int head, final int tail) {
    super.reset(buf, head, tail);
    index(buf, head, tail);
    return this;
  }

  @Override
  public JsonIterator reset(final char[] buf) {
    return new IndexedCharsJsonIterator(buf, 0, buf.length);
  }

  @Override
  public JsonIterator reset(final char[] buf, final int head, final int tail) {
    return new IndexedCharsJsonIterator(buf, head, tail);
  }

  // reset(InputStream) is inherited: it dispatches to reset(byte[]), which re-indexes.


  @Override
  public JsonIterator findField(final String field) {
    final int mark = head;
    if (skipUntil(field) != null) {
      return this;
    }
    // The cursor is just past the enclosing '}': rewind to its matching '{'
    // (safe on tokens: string contents are never structural) and rescan,
    // covering the fields before the original entry point.
    int p = syncTokens() - 1; // the consumed '}'
    for (int depth = 1; depth != 0; ) {
      if (--p < 0) {
        head = mark;
        return null; // no enclosing object
      }
      final char c = (char) (buf[tokens[p]] & 0xff);
      if (c == '}' || c == ']') {
        ++depth;
      } else if (c == '{' || c == '[') {
        --depth;
      }
    }
    pos = p;
    head = tokens[p];
    if (skipUntil(field) != null) {
      return this;
    }
    head = mark;
    return null;
  }

  /// Moves the token cursor to the first structural token at or after `head`.
  /// `head` is the source of truth: scalar reads advance it without touching
  /// the cursor, and both directions of drift are reconciled here.
  private int syncTokens() {
    int p = pos;
    final int[] tokens = this.tokens;
    while (p > 0 && tokens[p - 1] >= head) {
      --p;
    }
    while (tokens[p] < head) {
      ++p;
    }
    return p;
  }

  /// The closing quote is not a structural token; the next token is, and only
  /// whitespace can separate the two.
  @Override
  void skipPastEndQuote() {
    final int p = syncTokens();
    pos = p;
    head = tokens[p];
  }

  /// Only the first character of a number is a structural token; hop to the
  /// next token instead of scanning the remaining digits.
  @Override
  void skipUntilBreak() {
    final int p = syncTokens();
    pos = p;
    head = tokens[p];
  }

  @Override
  void skipContainer(final char open, final char close, int level) {
    int p = syncTokens();
    final int[] tokens = this.tokens;
    for (; ; ++p) {
      final int i = tokens[p];
      if (i >= tail) {
        throw reportError("skipContainer", "incomplete " + (open == '{' ? "object" : "array"));
      }
      final char c = (char) (buf[i] & 0xff);
      if (c == open) {
        ++level;
      } else if (c == close && --level == 0) {
        pos = p + 1;
        head = i + 1;
        return;
      }
    }
  }
}
