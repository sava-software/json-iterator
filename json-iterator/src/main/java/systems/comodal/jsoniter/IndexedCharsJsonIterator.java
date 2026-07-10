package systems.comodal.jsoniter;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

final class IndexedCharsJsonIterator extends CharsJsonIterator implements IndexedJsonIterator {

  private final StructuralIndex index;
  private int[] tokens;
  private int pos;

  IndexedCharsJsonIterator(final char[] buf, final int head, final int tail) {
    super(buf, head, tail);
    this.index = new StructuralIndex();
    index(buf, head, tail);
  }

  private void index(final char[] buf, final int from, final int to) {
    index.index(buf, from, to);
    this.tokens = index.indexes();
    this.pos = 0;
  }

  @Override
  public IndexedJsonIterator index() {
    return this;
  }

  @Override
  public JsonIterator reset(final byte[] buf) {
    return new IndexedBytesJsonIterator(buf, 0, buf.length, false);
  }

  @Override
  public JsonIterator reset(final byte[] buf, final int head, final int tail) {
    return new IndexedBytesJsonIterator(buf, head, tail, false);
  }

  @Override
  public JsonIterator reset(final char[] buf) {
    super.reset(buf);
    index(buf, 0, buf.length);
    return this;
  }

  @Override
  public JsonIterator reset(final char[] buf, final int head, final int tail) {
    super.reset(buf, head, tail);
    index(buf, head, tail);
    return this;
  }

  @Override
  public JsonIterator reset(final InputStream in) {
    try (in) {
      return reset(in.readAllBytes());
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public JsonIterator reset(final InputStream in, final int bufSize) {
    return reset(in);
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

  @Override
  void skipContainer(final char open, final char close, int level) {
    int p = syncTokens();
    final int[] tokens = this.tokens;
    for (; ; ++p) {
      final int i = tokens[p];
      if (i >= tail) {
        throw reportError("skipContainer", "incomplete " + (open == '{' ? "object" : "array"));
      }
      final char c = buf[i];
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
