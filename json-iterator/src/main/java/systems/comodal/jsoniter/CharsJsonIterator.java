package systems.comodal.jsoniter;

import jdk.incubator.vector.ShortVector;

import java.io.InputStream;
import java.math.BigDecimal;

class CharsJsonIterator extends BaseJsonIterator {

  private static final short QUOTE = '"';
  private static final short BACKSLASH = '\\';

  char[] buf;

  CharsJsonIterator(final char[] buf, final int head, final int tail) {
    super(head, tail);
    this.buf = buf;
  }

  @Override
  public IndexedJsonIterator index() {
    return new IndexedCharsJsonIterator(buf, head, tail);
  }

  @Override
  public JsonIterator reset(final byte[] buf) {
    return JsonIterator.parse(buf);
  }

  @Override
  public JsonIterator reset(final byte[] buf, final int head, final int tail) {
    return JsonIterator.parse(buf, head, tail);
  }

  @Override
  public JsonIterator reset(final char[] buf) {
    this.buf = buf;
    this.head = 0;
    this.tail = buf.length;
    return this;
  }

  @Override
  public JsonIterator reset(final char[] buf, final int head, final int tail) {
    this.buf = buf;
    this.head = head;
    this.tail = tail;
    return this;
  }

  @Override
  public JsonIterator reset(final InputStream in) {
    return JsonIterator.parse(in);
  }

  @Override
  public JsonIterator reset(final InputStream in, final int bufSize) {
    return JsonIterator.parse(in);
  }

  @Override
  String getBufferString(final int from, final int to) {
    return new String(buf, from, Math.min(to, tail) - from);
  }

  @Override
  char nextToken() {
    char c;
    for (int i = head; ; ) {
      c = buf[i++];
      // Tokens are almost always > ' ', so the common case is a single branch.
      if (c > ' ' || (c != ' ' && c != '\n' && c != '\t' && c != '\r')) {
        head = i;
        return c;
      }
    }
  }

  @Override
  char peekToken() {
    char c;
    for (int i = head; ; i++) {
      c = buf[i];
      if (c > ' ' || (c != ' ' && c != '\n' && c != '\t' && c != '\r')) {
        head = i;
        return c;
      }
    }
  }

  @Override
  char readChar() {
    return buf[head++];
  }

  @Override
  char peekChar() {
    return buf[head];
  }

  @Override
  char peekChar(final int offset) {
    return buf[offset];
  }

  @Override
  int peekIntDigitChar(final int offset) {
    return INT_DIGITS[buf[offset]];
  }

  @Override
  int parse() {
    return parse(head);
  }

  private int numEscapes = 0;

  private int parse(final int from) {
    numEscapes = 0;
    final int lanes = VectorSupport.SHORT_LANES;
    int i = from;
    for (; i + lanes <= tail; i += lanes) {
      final var chunk = ShortVector.fromCharArray(VectorSupport.SHORT_SPECIES, buf, i);
      final long backslash = chunk.eq(BACKSLASH).toLong();
      final long quote = chunk.eq(QUOTE).toLong();
      if (quote != 0 && (backslash == 0 || Long.numberOfTrailingZeros(quote) < Long.numberOfTrailingZeros(backslash))) {
        final int end = i + Long.numberOfTrailingZeros(quote);
        head = end + 1;
        return end - from;
      } else if (backslash != 0) {
        // Escape sequences need pairwise skipping and counting; continue scalar.
        return parseScalar(from, i + Long.numberOfTrailingZeros(backslash));
      }
    }
    return parseScalar(from, i);
  }

  private int parseScalar(final int from, int i) {
    for (char c; ; i++) {
      if (i >= tail) {
        throw reportError("parse", "incomplete string");
      }
      c = buf[i];
      if (c == '"') {
        head = i + 1;
        return i - from;
      } else if (c == '\\') {
        ++numEscapes;
        ++i;
      }
    }
  }

  @Override
  void skipPastEndQuote() {
    final int lanes = VectorSupport.SHORT_LANES;
    int i = head;
    for (; i + lanes <= tail; i += lanes) {
      final var chunk = ShortVector.fromCharArray(VectorSupport.SHORT_SPECIES, buf, i);
      final long backslash = chunk.eq(BACKSLASH).toLong();
      final long quote = chunk.eq(QUOTE).toLong();
      if (quote != 0 && (backslash == 0 || Long.numberOfTrailingZeros(quote) < Long.numberOfTrailingZeros(backslash))) {
        head = i + Long.numberOfTrailingZeros(quote) + 1;
        return;
      } else if (backslash != 0) {
        skipPastEndQuoteScalar(i + Long.numberOfTrailingZeros(backslash));
        return;
      }
    }
    skipPastEndQuoteScalar(i);
  }

  private void skipPastEndQuoteScalar(int i) {
    char c;
    while (i < tail) {
      c = buf[i++];
      if (c == '"') {
        head = i;
        return;
      } else if (c == '\\') {
        ++i;
      }
    }
    throw reportError("skipPastEndQuote", "incomplete string");
  }

  @Override
  void skipContainer(final char open, final char close, int level) {
    final int lanes = VectorSupport.SHORT_LANES;
    int h = head;
    outer:
    while (h + lanes <= tail) {
      final var chunk = ShortVector.fromCharArray(VectorSupport.SHORT_SPECIES, buf, h);
      long bits = chunk.eq((short) open).toLong() | chunk.eq((short) close).toLong() | chunk.eq(QUOTE).toLong();
      while (bits != 0) {
        final int n = Long.numberOfTrailingZeros(bits);
        final char c = buf[h + n];
        if (c == '"') {
          head = h + n + 1;
          skipPastEndQuote();
          h = head;
          continue outer;
        } else if (c == open) {
          ++level;
        } else if (--level == 0) {
          head = h + n + 1;
          return;
        }
        bits &= bits - 1;
      }
      h += lanes;
    }
    head = h;
    super.skipContainer(open, close, level);
  }

  @Override
  int parseNumber() {
    for (int i = head, len = 0; ; i++) {
      if (i == tail) {
        head = tail;
        return len;
      }
      switch (peekChar(i)) {
        case ' ' -> {
        }
        case '.', 'e', 'E', '-', '+', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> len++;
        default -> {
          head = i;
          return len;
        }
      }
    }
  }

  private char[] handleEscapes(final int from, final int len) {
    final char[] chars = new char[len - numEscapes];
    char c;
    for (int i = 0, j = from; i < chars.length; i++, j++) {
      c = buf[j];
      if (c == '\\') {
        c = buf[++j];
      }
      chars[i] = c;
    }
    return chars;
  }

  @Override
  <R> R parse(final CharBufferFunction<R> applyChars) {
    final int from = head;
    final int len = parse(from);
    if (numEscapes > 0) {
      final char[] chars = handleEscapes(from, len);
      return applyChars.apply(chars, 0, chars.length);
    } else {
      return applyChars.apply(buf, from, len);
    }
  }

  @Override
  <C, R> R parse(final C context, final ContextCharBufferFunction<C, R> applyChars) {
    final int from = head;
    final int len = parse(from);
    if (numEscapes > 0) {
      final char[] chars = handleEscapes(from, len);
      return applyChars.apply(context, chars, 0, chars.length);
    } else {
      return applyChars.apply(context, buf, from, len);
    }
  }

  @Override
  int parse(final CharBufferToIntFunction applyChars) {
    final int from = head;
    final int len = parse(from);
    if (numEscapes > 0) {
      final char[] chars = handleEscapes(from, len);
      return applyChars.applyAsInt(chars, 0, chars.length);
    } else {
      return applyChars.applyAsInt(buf, from, len);
    }
  }

  @Override
  <C> int parse(final C context, final ContextCharBufferToIntFunction<C> applyChars) {
    final int from = head;
    final int len = parse(from);
    if (numEscapes > 0) {
      final char[] chars = handleEscapes(from, len);
      return applyChars.applyAsInt(context, chars, 0, chars.length);
    } else {
      return applyChars.applyAsInt(context, buf, from, len);
    }
  }

  @Override
  long parse(final CharBufferToLongFunction applyChars) {
    final int from = head;
    final int len = parse(from);
    if (numEscapes > 0) {
      final char[] chars = handleEscapes(from, len);
      return applyChars.applyAsLong(chars, 0, chars.length);
    } else {
      return applyChars.applyAsLong(buf, from, len);
    }
  }

  @Override
  <C> long parse(final C context, final ContextCharBufferToLongFunction<C> applyChars) {
    final int from = head;
    final int len = parse(from);
    if (numEscapes > 0) {
      final char[] chars = handleEscapes(from, len);
      return applyChars.applyAsLong(context, chars, 0, chars.length);
    } else {
      return applyChars.applyAsLong(context, buf, from, len);
    }
  }

  @Override
  boolean parse(final CharBufferPredicate testChars) {
    final int from = head;
    final int len = parse(from);
    if (numEscapes > 0) {
      final char[] chars = handleEscapes(from, len);
      return testChars.apply(chars, 0, chars.length);
    } else {
      return testChars.apply(buf, from, len);
    }
  }

  @Override
  <C> boolean parse(final C context, final ContextCharBufferPredicate<C> testChars) {
    final int from = head;
    final int len = parse(from);
    if (numEscapes > 0) {
      final char[] chars = handleEscapes(from, len);
      return testChars.apply(context, chars, 0, chars.length);
    } else {
      return testChars.apply(context, buf, from, len);
    }
  }

  @Override
  void parse(final CharBufferConsumer testChars) {
    final int from = head;
    final int len = parse(from);
    if (numEscapes > 0) {
      final char[] chars = handleEscapes(from, len);
      testChars.accept(chars, 0, chars.length);
    } else {
      testChars.accept(buf, from, len);
    }
  }

  @Override
  <C> void parse(final C context, final ContextCharBufferConsumer<C> testChars) {
    final int from = head;
    final int len = parse(from);
    if (numEscapes > 0) {
      final char[] chars = handleEscapes(from, len);
      testChars.accept(context, chars, 0, chars.length);
    } else {
      testChars.accept(context, buf, from, len);
    }
  }

  @Override
  boolean parseFieldEquals(final String field) {
    final int fieldLength = field.length();
    int i = head;
    for (int f = 0; f < fieldLength; ++f, ++i) {
      if (i >= tail) {
        return parseFieldEqualsSlow(field); // reports the incomplete string
      }
      final char c = buf[i];
      if (c == '\\') {
        // Escaped field names take the unescaping slow path.
        return parseFieldEqualsSlow(field);
      } else if (c != field.charAt(f)) {
        skipPastEndQuote();
        return false;
      }
    }
    if (i < tail && buf[i] == '"') {
      head = i + 1;
      return true;
    }
    // The name continues past the compared prefix, or the buffer ended.
    skipPastEndQuote();
    return false;
  }

  private boolean parseFieldEqualsSlow(final String field) {
    final int from = head;
    final int len = parse(from);
    if (numEscapes > 0) {
      final char[] chars = handleEscapes(from, len);
      return JsonIterator.fieldEquals(field, chars, 0, chars.length);
    }
    return JsonIterator.fieldEquals(field, buf, from, len);
  }

  @Override
  boolean breakOut(final FieldBufferPredicate fieldBufferFunction, final int offset, final int len) {
    if (numEscapes > 0) {
      final char[] chars = handleEscapes(offset, len);
      return !fieldBufferFunction.test(chars, 0, chars.length, this);
    } else {
      return !fieldBufferFunction.test(buf, offset, len, this);
    }
  }

  @Override
  <C> boolean breakOut(final C context,
                       final ContextFieldBufferPredicate<C> fieldBufferFunction,
                       final int offset,
                       final int len) {
    if (numEscapes > 0) {
      final char[] chars = handleEscapes(offset, len);
      return !fieldBufferFunction.test(context, chars, 0, chars.length, this);
    } else {
      return !fieldBufferFunction.test(context, buf, offset, len, this);
    }
  }

  @Override
  <C> long test(final C context,
                final long mask,
                final ContextFieldBufferMaskedPredicate<C> fieldBufferFunction,
                final int offset,
                final int len) {
    if (numEscapes > 0) {
      final char[] chars = handleEscapes(offset, len);
      return fieldBufferFunction.test(context, mask, chars, 0, chars.length, this);
    } else {
      return fieldBufferFunction.test(context, mask, buf, offset, len, this);
    }
  }

  @Override
  <R> R apply(final FieldBufferFunction<R> fieldBufferFunction, final int offset, final int len) {
    if (numEscapes > 0) {
      final char[] chars = handleEscapes(offset, len);
      return fieldBufferFunction.apply(chars, 0, chars.length, this);
    } else {
      return fieldBufferFunction.apply(buf, offset, len, this);
    }
  }

  @Override
  <C, R> R apply(final C context,
                 final ContextFieldBufferFunction<C, R> fieldBufferFunction,
                 final int offset,
                 final int len) {
    if (numEscapes > 0) {
      final char[] chars = handleEscapes(offset, len);
      return fieldBufferFunction.apply(context, chars, 0, chars.length, this);
    } else {
      return fieldBufferFunction.apply(context, buf, offset, len, this);
    }
  }

  @Override
  BigDecimal parseBigDecimal(final CharBufferFunction<BigDecimal> parseChars) {
    final int len = parseNumber();
    return parseChars.apply(buf, head - len, len);
  }

  @Override
  String parsedNumberAsString(final int len) {
    return new String(buf, head - len, len);
  }

  @Override
  <R> R parseNumber(final CharBufferFunction<R> applyChars, final int len) {
    return applyChars.apply(buf, head - len, len);
  }

  @Override
  <C, R> R parseNumber(final C context,
                       final ContextCharBufferFunction<C, R> applyChars,
                       final int len) {
    return applyChars.apply(context, buf, head - len, len);
  }

  @Override
  int parseNumber(final CharBufferToIntFunction applyChars, final int len) {
    return applyChars.applyAsInt(buf, head - len, len);
  }

  @Override
  <C> int parseNumber(final C context, final ContextCharBufferToIntFunction<C> applyChars, final int len) {
    return applyChars.applyAsInt(context, buf, head - len, len);
  }

  @Override
  long parseNumber(final CharBufferToLongFunction applyChars, final int len) {
    return applyChars.applyAsLong(buf, head - len, len);
  }

  @Override
  <C> long parseNumber(final C context, final ContextCharBufferToLongFunction<C> applyChars, final int len) {
    return applyChars.applyAsLong(context, buf, head - len, len);
  }
}
