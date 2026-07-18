package systems.comodal.jsoniter;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Arrays;

final class CharsJsonIterator extends BaseJsonIterator {

  private char[] buf;

  CharsJsonIterator(final char[] buf, final int head, final int tail) {
    super(head, tail);
    this.buf = buf;
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
  String getBufferString(final int from, final int to) {
    return new String(buf, from, Math.min(to, tail) - from);
  }

  @Override
  char nextToken() {
    char c;
    for (int i = head; ; ) {
      if (i == tail) {
        throw reportError("nextToken", "unexpected end");
      }
      c = buf[i++];
      switch (c) {
        case ' ', '\n', '\r', '\t' -> {
        }
        default -> {
          head = i;
          return c;
        }
      }
    }
  }

  @Override
  char peekToken() {
    char c;
    for (int i = head; ; i++) {
      if (i == tail) {
        throw reportError("peekToken", "unexpected end");
      }
      c = buf[i];
      switch (c) {
        case ' ', '\n', '\r', '\t' -> {
        }
        default -> {
          head = i;
          return c;
        }
      }
    }
  }

  @Override
  char readChar() {
    if (head == tail) {
      throw reportError("readChar", "unexpected end");
    }
    return buf[head++];
  }

  @Override
  char peekChar() {
    if (head == tail) {
      throw reportError("peekChar", "unexpected end");
    }
    return buf[head];
  }

  @Override
  char peekChar(final int offset) {
    return buf[offset];
  }

  @Override
  int peekIntDigitChar(final int offset) {
    return intDigit(buf[offset]);
  }

  @Override
  int parse() {
    return parse(head);
  }

  private int numEscapes = 0;

  private int parse(final int from) {
    char c;
    numEscapes = 0;
    for (int i = from; ; i++) {
      if (i >= tail) {
        throw reportError("parse", "incomplete string");
      }
      c = peekChar(i);
      if (c == '"') {
        head = i + 1;
        return i - from;
      } else if (c == '\\') {
        ++numEscapes;
        ++i;
      }
    }
  }

  // Escape validation mirrors BytesJsonIterator#skipPastMultiByteEndQuote so
  // both sources reject the same malformed documents when a value is skipped
  // rather than read.
  @Override
  void skipPastEndQuote() {
    char c;
    boolean isExpectingLowSurrogate = false;
    while (head < tail) {
      c = buf[head++];
      if (c == '"') {
        return;
      } else if (c == '\\') {
        if (head == tail) {
          break;
        }
        c = buf[head++];
        switch (c) {
          case 'b', 't', 'n', 'f', 'r', '"', '/', '\\' -> {
          }
          case 'u' -> {
            if (head == tail) {
              throw reportError("skipPastEndQuote", "incomplete string");
            }
            int bc = JHex.decode(buf[head++]) << 12;
            if (head == tail) {
              throw reportError("skipPastEndQuote", "incomplete string");
            }
            bc += JHex.decode(buf[head++]) << 8;
            if (head == tail) {
              throw reportError("skipPastEndQuote", "incomplete string");
            }
            bc += JHex.decode(buf[head++]) << 4;
            if (head == tail) {
              throw reportError("skipPastEndQuote", "incomplete string");
            }
            bc += JHex.decode(buf[head++]);
            if (isExpectingLowSurrogate) {
              if (Character.isLowSurrogate((char) bc)) {
                isExpectingLowSurrogate = false;
              } else {
                throw new JsonException("invalid surrogate");
              }
            } else if (Character.isHighSurrogate((char) bc)) {
              isExpectingLowSurrogate = true;
            } else if (Character.isLowSurrogate((char) bc)) {
              throw new JsonException("invalid surrogate");
            }
          }
          default -> throw reportError("skipPastEndQuote", "invalid escape character: " + c);
        }
      }
    }
    throw reportError("skipPastEndQuote", "incomplete string");
  }

  @Override
  int parseNumber() {
    for (int i = head, len = 0; ; i++) {
      if (i == tail) {
        head = tail;
        return len;
      }
      switch (peekChar(i)) {
        // entered without a peekToken from the applyNumberChars family, so
        // leading whitespace is this scan's job — but past the first token
        // char whitespace must terminate: consumers slice this buffer as
        // [head - len, head), and a swallowed char shifts that window over
        // the leading digits
        case ' ', '\t', '\n', '\r' -> {
          if (len != 0) {
            head = i;
            return len;
          }
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
    // Each simple escape shrinks the output by one char and each unicode
    // escape by five, so this is an upper bound; trimmed below when unicode
    // escapes occurred. Escape semantics mirror BytesJsonIterator#parseMultiByteString.
    final char[] chars = new char[len - numEscapes];
    char c;
    int i = 0;
    boolean expectingLowSurrogate = false;
    for (int j = from, to = from + len; j < to; ++j, ++i) {
      c = buf[j];
      if (c == '\\') {
        c = buf[++j];
        switch (c) {
          case 'b' -> c = '\b';
          case 't' -> c = '\t';
          case 'n' -> c = '\n';
          case 'f' -> c = '\f';
          case 'r' -> c = '\r';
          case '"', '/', '\\' -> {
          }
          case 'u' -> {
            c = (char) ((JHex.decode(buf[++j]) << 12)
                + (JHex.decode(buf[++j]) << 8)
                + (JHex.decode(buf[++j]) << 4)
                + JHex.decode(buf[++j]));
            if (expectingLowSurrogate) {
              if (Character.isLowSurrogate(c)) {
                expectingLowSurrogate = false;
              } else {
                throw new JsonException("invalid surrogate");
              }
            } else if (Character.isHighSurrogate(c)) {
              expectingLowSurrogate = true;
            } else if (Character.isLowSurrogate(c)) {
              throw new JsonException("invalid surrogate");
            }
          }
          default -> throw reportError("handleEscapes", "invalid escape character: " + c);
        }
      }
      chars[i] = c;
    }
    return i == chars.length ? chars : Arrays.copyOf(chars, i);
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
  double parse(final CharBufferToDoubleFunction applyChars) {
    final int from = head;
    final int len = parse(from);
    if (numEscapes > 0) {
      final char[] chars = handleEscapes(from, len);
      return applyChars.applyAsDouble(chars, 0, chars.length);
    } else {
      return applyChars.applyAsDouble(buf, from, len);
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

  // Field name span for the matcher hook: buf itself, or the unescaped copy.
  private char[] fieldChars;
  private int fieldCharsOffset;

  /// Returns the name's char length.
  @Override
  int parseFieldName() {
    final int from = head;
    final int len = parse(from);
    if (numEscapes > 0) {
      final char[] chars = handleEscapes(from, len);
      fieldChars = chars;
      fieldCharsOffset = 0;
      return chars.length;
    } else {
      fieldChars = buf;
      fieldCharsOffset = from;
      return len;
    }
  }

  @Override
  int matchField(final FieldMatcher matcher, final int len) {
    return matcher.match(fieldChars, fieldCharsOffset, len);
  }

  @Override
  String fieldString(final int len) {
    return new String(fieldChars, fieldCharsOffset, len);
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
  <C> boolean breakOut(final C context, final ContextFieldBufferPredicate<C> fieldBufferFunction, final int offset, final int len) {
    if (numEscapes > 0) {
      final char[] chars = handleEscapes(offset, len);
      return !fieldBufferFunction.test(context, chars, 0, chars.length, this);
    } else {
      return !fieldBufferFunction.test(context, buf, offset, len, this);
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
  <C, R> R apply(final C context, final ContextFieldBufferFunction<C, R> fieldBufferFunction, final int offset, final int len) {
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
  double parseNumber(final CharBufferToDoubleFunction applyChars, final int len) {
    return applyChars.applyAsDouble(buf, head - len, len);
  }

  @Override
  <C> long parseNumber(final C context, final ContextCharBufferToLongFunction<C> applyChars, final int len) {
    return applyChars.applyAsLong(context, buf, head - len, len);
  }
}
