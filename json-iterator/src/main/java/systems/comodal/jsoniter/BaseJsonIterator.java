package systems.comodal.jsoniter;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

import static systems.comodal.jsoniter.ContextFieldBufferMaskedPredicate.BREAK_OUT;
import static systems.comodal.jsoniter.ValueType.*;

abstract class BaseJsonIterator implements JsonIterator {

  protected static final CharBufferFunction<String> READ_STRING_FUNCTION = String::new;

  static final int INVALID_CHAR_FOR_NUMBER = -1;
  static final int[] INT_DIGITS = INIT_INT_DIGITS.initIntDigits();

  private static final CharBufferFunction<BigDecimal> READ_BIG_DECIMAL_FUNCTION = (chars, offset, len) -> len == 0 ? null : new BigDecimal(chars, offset, len);

  private static final CharBufferFunction<BigDecimal> READ_BIG_DECIMAL_DROP_TRAILING_ZEROES_FUNCTION = (chars, offset, len) -> {
    if (len == 1) {
      return chars[offset] == '0'
          ? BigDecimal.ZERO
          : new BigDecimal(chars, offset, len);
    } else if (len == 0) {
      return null;
    }
    int pos = (offset + len) - 1;
    if (chars[pos] != '0') {
      return new BigDecimal(chars, offset, len);
    }
    char c = chars[--pos];
    while (c == '0') {
      if (pos == offset) {
        return BigDecimal.ZERO;
      }
      c = chars[--pos];
    }
    for (int j = pos; c != '.'; c = chars[--j]) {
      if ((c == 'e') || (c == 'E')) {
        return new BigDecimal(chars, offset, len).stripTrailingZeros();
      } else if (j == offset) { // Not a decimal
        return new BigDecimal(chars, offset, len);
      }
    }
    return new BigDecimal(chars, offset, (pos + 1) - offset);
  };

  private static final CharBufferFunction<BigInteger> READ_BIG_INTEGER_FUNCTION = (chars, offset, len) -> new BigInteger(new String(chars, offset, len));

  int head;
  int tail;

  BaseJsonIterator(final int head, final int tail) {
    this.head = head;
    this.tail = tail;
  }

  @Override
  public int mark() {
    return head;
  }

  @Override
  public JsonIterator reset(final int mark) {
    this.head = mark;
    return this;
  }

  abstract String getBufferString(final int from, final int to);

  final JsonException reportError(final String op, final String msg) {
    final var peek = getBufferString(head <= 10 ? 0 : head - 10, Math.min(head, tail));
    throw new JsonException(op + ": " + msg + ", head: " + head + ", peek: " + peek + ", buf: " + getBufferString(0, 1_024));
  }

  @Override
  public final String currentBuffer() {
    final var peek = getBufferString(head <= 10 ? 0 : head - 10, head);
    return "head: " + head + ", peek: " + peek + ", buf: " + getBufferString(0, 1_024);
  }

  abstract char readChar();

  abstract char peekChar();

  abstract char peekChar(final int offset);

  abstract int peekIntDigitChar(final int offset);

  abstract char nextToken();

  abstract char peekToken();

  protected final void skip(final int n) {
    head += n;
    if (head > tail) {
      throw reportError("skip", "unexpected end");
    }
  }

  @Override
  public final JsonIterator openArray() {
    final char c = nextToken();
    if (c == '[') {
      return this;
    } else {
      throw reportError("openArray", "expected '[' but found: " + c);
    }
  }

  @Override
  public final JsonIterator continueArray() {
    final char c = nextToken();
    if (c == ',') {
      return this;
    } else {
      throw reportError("continueArray", "expected ',' but found: " + c);
    }
  }

  @Override
  public final JsonIterator closeArray() {
    final char c = nextToken();
    if (c == ']') {
      return this;
    } else {
      throw reportError("closeArray", "expected ']' but found: " + c);
    }
  }

  @Override
  public final String readString() {
    final char c = nextToken();
    if (c == '"') {
      return parseString();
    } else if (c == 'n') {
      skip(3);
      return null;
    } else {
      throw reportError("readString", "expected string or null, but " + c);
    }
  }

  @Override
  public byte[] decodeBase64String() {
    return Base64.getDecoder().decode(readString());
  }

  abstract int parse();

  abstract void skipPastEndQuote();

  @Override
  public final <R> R applyChars(final CharBufferFunction<R> applyChars) {
    final char c = nextToken();
    if (c == '"') {
      return parse(applyChars);
    } else if (c == 'n') {
      skip(3);
      return null;
    } else {
      throw reportError("applyChars", "expected string or null, but " + c);
    }
  }

  abstract <R> R parse(final CharBufferFunction<R> applyChars);

  @Override
  public final <C, R> R applyChars(final C context, final ContextCharBufferFunction<C, R> applyChars) {
    final char c = nextToken();
    if (c == '"') {
      return parse(context, applyChars);
    } else if (c == 'n') {
      skip(3);
      return null;
    } else {
      throw reportError("applyChars", "expected string or null, but " + c);
    }
  }

  abstract <C, R> R parse(final C context, final ContextCharBufferFunction<C, R> applyChars);

  @Override
  public final int applyCharsAsInt(final CharBufferToIntFunction applyChars) {
    final char c = nextToken();
    if (c == '"') {
      return parse(applyChars);
    } else if (c == 'n') {
      skip(3);
      return applyChars.applyAsInt(new char[0], 0, 0);
    } else {
      throw reportError("applyCharsAsInt", "expected string or null, but " + c);
    }
  }

  abstract int parse(final CharBufferToIntFunction applyChars);

  @Override
  public final <C> int applyCharsAsInt(final C context, final ContextCharBufferToIntFunction<C> applyChars) {
    final char c = nextToken();
    if (c == '"') {
      return parse(context, applyChars);
    } else if (c == 'n') {
      skip(3);
      return applyChars.applyAsInt(context, new char[0], 0, 0);
    } else {
      throw reportError("applyCharsAsInt", "expected string or null, but " + c);
    }
  }

  abstract <C> int parse(final C context, final ContextCharBufferToIntFunction<C> applyChars);

  @Override
  public final long applyCharsAsLong(final CharBufferToLongFunction applyChars) {
    final char c = nextToken();
    if (c == '"') {
      return parse(applyChars);
    } else if (c == 'n') {
      skip(3);
      return applyChars.applyAsLong(new char[0], 0, 0);
    } else {
      throw reportError("applyCharsAsLong", "expected string or null, but " + c);
    }
  }

  abstract long parse(final CharBufferToLongFunction applyChars);

  @Override
  public final <C> long applyCharsAsLong(final C context, final ContextCharBufferToLongFunction<C> applyChars) {
    final char c = nextToken();
    if (c == '"') {
      return parse(context, applyChars);
    } else if (c == 'n') {
      skip(3);
      return applyChars.applyAsLong(context, new char[0], 0, 0);
    } else {
      throw reportError("applyCharsAsLong", "expected string or null, but " + c);
    }
  }

  abstract <C> long parse(final C context, final ContextCharBufferToLongFunction<C> applyChars);

  @Override
  public final boolean testChars(final CharBufferPredicate testChars) {
    final char c = nextToken();
    if (c == '"') {
      return parse(testChars);
    } else if (c == 'n') {
      skip(3);
      return false;
    } else {
      throw reportError("testChars", "expected string or null, but " + c);
    }
  }

  abstract boolean parse(final CharBufferPredicate testChars);

  @Override
  public final <C> boolean testChars(final C context, final ContextCharBufferPredicate<C> testChars) {
    final char c = nextToken();
    if (c == '"') {
      return parse(context, testChars);
    } else if (c == 'n') {
      skip(3);
      return false;
    } else {
      throw reportError("testChars", "expected string or null, but " + c);
    }
  }

  abstract <C> boolean parse(final C context, final ContextCharBufferPredicate<C> testChars);

  @Override
  public final void consumeChars(final CharBufferConsumer testChars) {
    final char c = nextToken();
    if (c == '"') {
      parse(testChars);
    } else if (c == 'n') {
      skip(3);
    } else {
      throw reportError("consumeChars", "expected string or null, but " + c);
    }
  }

  abstract void parse(final CharBufferConsumer testChars);

  @Override
  public final <C> void consumeChars(final C context, final ContextCharBufferConsumer<C> testChars) {
    final char c = nextToken();
    if (c == '"') {
      parse(context, testChars);
    } else if (c == 'n') {
      skip(3);
    } else {
      throw reportError("consumeChars", "expected string or null, but " + c);
    }
  }

  abstract <C> void parse(final C context, final ContextCharBufferConsumer<C> testChars);

  abstract boolean fieldEquals(final String field, final int offset, final int len);

  @Override
  public final JsonIterator skipUntil(final String field) {
    char c;
    for (int offset, len; ; ) {
      if ((c = nextToken()) == ',') {
        c = nextToken();
        if (c != '"') {
          throw reportError("skipUntil", "expected string field, but " + c);
        } else {
          offset = head;
          len = parse();
          if ((c = nextToken()) != ':') {
            throw reportError("skipUntil", "expected :, but " + c);
          } else if (fieldEquals(field, offset, len)) {
            return this;
          } else {
            skip();
          }
        }
      } else if (c == '{') {
        c = nextToken();
        if (c == '"') {
          offset = head;
          len = parse();
          if ((c = nextToken()) != ':') {
            throw reportError("skipUntil", "expected :, but " + c);
          } else if (fieldEquals(field, offset, len)) {
            return this;
          } else {
            skip();
          }
        } else if (c == '}') { // end of object
          return null;
        } else {
          throw reportError("skipUntil", "expected \" after {");
        }
      } else if (c == '}') {
        return null;
      } else {
        throw reportError("skipUntil", "expected [\\{\\}n], but found: " + c);
      }
    }
  }

  @Override
  public final boolean testObjField(final CharBufferPredicate testField) {
    char c = nextToken();
    if (c == ',') {
      c = nextToken();
      if (c != '"') {
        throw reportError("testObjField", "expected field string, but " + c);
      } else {
        final boolean result = parse(testField);
        if ((c = nextToken()) != ':') {
          throw reportError("testObjField", "expected :, but " + c);
        } else {
          return result;
        }
      }
    } else if (c == '{') {
      c = nextToken();
      if (c == '"') {
        final boolean result = parse(testField);
        if ((c = nextToken()) != ':') {
          throw reportError("testObjField", "expected :, but " + c);
        } else {
          return result;
        }
      } else if (c == '}') {
        return false; // empty object
      } else {
        throw reportError("testObjField", "expected \" after {");
      }
    } else if (c == '}') {
      return false; // end of object
    } else if (c == 'n') {
      skip(3);
      return false;
    } else {
      throw reportError("testObjField", "expected [\\{\\}n], but found: " + c);
    }
  }

  @Override
  public final <R> R applyObjField(final CharBufferFunction<R> applyChars) {
    char c = nextToken();
    if (c == ',') {
      c = nextToken();
      if (c != '"') {
        throw reportError("applyObjField", "expected field string, but " + c);
      } else {
        final var result = parse(applyChars);
        if ((c = nextToken()) != ':') {
          throw reportError("applyObjField", "expected :, but " + c);
        } else {
          return result;
        }
      }
    } else if (c == '{') {
      c = nextToken();
      if (c == '"') {
        final var result = parse(applyChars);
        if ((c = nextToken()) != ':') {
          throw reportError("applyObjField", "expected :, but " + c);
        } else {
          return result;
        }
      } else if (c == '}') {
        return null; // empty object
      } else {
        throw reportError("applyObjField", "expected \" after {");
      }
    } else if (c == '}') {
      return null; // end of object
    } else if (c == 'n') {
      final var result = applyChars.apply(new char[0], 0, 0);
      skip(3);
      return result;
    } else {
      throw reportError("applyObjField", "expected [\\{\\}n], but found: " + c);
    }
  }

  @Override
  public final int applyObjFieldAsInt(final CharBufferToIntFunction applyChars, final int terminalSentinel) {
    char c = nextToken();
    if (c == ',') {
      c = nextToken();
      if (c != '"') {
        throw reportError("applyObjFieldAsInt", "expected field string, but " + c);
      } else {
        final var result = parse(applyChars);
        if ((c = nextToken()) != ':') {
          throw reportError("applyObjFieldAsInt", "expected :, but " + c);
        } else {
          return result;
        }
      }
    } else if (c == '{') {
      c = nextToken();
      if (c == '"') {
        final var result = parse(applyChars);
        if ((c = nextToken()) != ':') {
          throw reportError("applyObjFieldAsInt", "expected :, but " + c);
        } else {
          return result;
        }
      } else if (c == '}') {
        return terminalSentinel; // empty object
      } else {
        throw reportError("applyObjFieldAsInt", "expected \" after {");
      }
    } else if (c == '}') {
      return terminalSentinel; // end of object
    } else if (c == 'n') {
      final var result = applyChars.applyAsInt(new char[0], 0, 0);
      skip(3); // null
      return result;
    } else {
      throw reportError("applyObjFieldAsInt", "expected [\\{\\}n], but found: " + c);
    }
  }

  @Override
  public final <C> int applyObjFieldAsInt(final C context, final ContextCharBufferToIntFunction<C> applyChars, final int terminalSentinel) {
    char c = nextToken();
    if (c == ',') {
      c = nextToken();
      if (c != '"') {
        throw reportError("applyObjFieldAsInt", "expected field string, but " + c);
      } else {
        final var result = parse(context, applyChars);
        if ((c = nextToken()) != ':') {
          throw reportError("applyObjFieldAsInt", "expected :, but " + c);
        } else {
          return result;
        }
      }
    } else if (c == '{') {
      c = nextToken();
      if (c == '"') {
        final var result = parse(context, applyChars);
        if ((c = nextToken()) != ':') {
          throw reportError("applyObjFieldAsInt", "expected :, but " + c);
        } else {
          return result;
        }
      } else if (c == '}') {
        return terminalSentinel; // empty object
      } else {
        throw reportError("applyObjFieldAsInt", "expected \" after {");
      }
    } else if (c == '}') {
      return terminalSentinel; // end of object
    } else if (c == 'n') {
      final var result = applyChars.applyAsInt(context, new char[0], 0, 0);
      skip(3); // null
      return result;
    } else {
      throw reportError("applyObjFieldAsInt", "expected [\\{\\}n], but found: " + c);
    }
  }

  @Override
  public final long applyObjFieldAsLong(final CharBufferToLongFunction applyChars, final long terminalSentinel) {
    char c = nextToken();
    if (c == ',') {
      c = nextToken();
      if (c != '"') {
        throw reportError("applyObjFieldAsLong", "expected field string, but " + c);
      } else {
        final var result = parse(applyChars);
        if ((c = nextToken()) != ':') {
          throw reportError("applyObjFieldAsLong", "expected :, but " + c);
        } else {
          return result;
        }
      }
    } else if (c == '{') {
      c = nextToken();
      if (c == '"') {
        final var result = parse(applyChars);
        if ((c = nextToken()) != ':') {
          throw reportError("applyObjFieldAsLong", "expected :, but " + c);
        } else {
          return result;
        }
      } else if (c == '}') {
        return terminalSentinel; // empty object
      } else {
        throw reportError("applyObjFieldAsLong", "expected \" after {");
      }
    } else if (c == '}') {
      return terminalSentinel; // end of object
    } else if (c == 'n') {
      final var result = applyChars.applyAsLong(new char[0], 0, 0);
      skip(3); // null
      return result;
    } else {
      throw reportError("applyObjFieldAsLong", "expected [\\{\\}n], but found: " + c);
    }
  }

  @Override
  public final <C> long applyObjFieldAsLong(final C context, final ContextCharBufferToLongFunction<C> applyChars, final long terminalSentinel) {
    char c = nextToken();
    if (c == ',') {
      c = nextToken();
      if (c != '"') {
        throw reportError("applyObjFieldAsLong", "expected field string, but " + c);
      } else {
        final var result = parse(context, applyChars);
        if ((c = nextToken()) != ':') {
          throw reportError("applyObjFieldAsLong", "expected :, but " + c);
        } else {
          return result;
        }
      }
    } else if (c == '{') {
      c = nextToken();
      if (c == '"') {
        final var result = parse(context, applyChars);
        if ((c = nextToken()) != ':') {
          throw reportError("applyObjFieldAsLong", "expected :, but " + c);
        } else {
          return result;
        }
      } else if (c == '}') {
        return terminalSentinel; // empty object
      } else {
        throw reportError("applyObjFieldAsLong", "expected \" after {");
      }
    } else if (c == '}') {
      return terminalSentinel; // end of object
    } else if (c == 'n') {
      final var result = applyChars.applyAsLong(context, new char[0], 0, 0);
      skip(3); // null
      return result;
    } else {
      throw reportError("applyObjFieldAsLong", "expected [\\{\\}n], but found: " + c);
    }
  }

  protected String parseString() {
    return parse(READ_STRING_FUNCTION);
  }

  @Override
  public final String readObjField() {
    char c = nextToken();
    if (c == ',') {
      c = nextToken();
      if (c == '"') {
        final var field = parseString();
        if ((c = nextToken()) == ':') {
          return field;
        } else {
          throw reportError("readObjField", "expected :, but " + c);
        }
      } else {
        throw reportError("readObjField", "expected field string, but " + c);
      }
    } else if (c == '{') {
      c = nextToken();
      if (c == '"') {
        final var field = parseString();
        if ((c = nextToken()) == ':') {
          return field;
        } else {
          throw reportError("readObjField", "expected :, but " + c);
        }
      } else if (c == '}') {
        return null; // empty object
      } else {
        throw reportError("readObjField", "expected \" after {");
      }
    } else if (c == '}') {
      return null; // end of object
    } else if (c == 'n') {
      skip(3);
      return null;
    } else {
      throw reportError("readObjField", "expected [\\{\\}n], but found: " + c);
    }
  }

  public final JsonIterator skipObjField() {
    char c = nextToken();
    if (c == ',') {
      c = nextToken();
      if (c != '"') {
        throw reportError("skipObjField", "expected string field, but " + c);
      } else {
        skipPastEndQuote();
        if ((c = nextToken()) == ':') {
          return this;
        } else {
          throw reportError("skipObjField", "expected :, but " + c);
        }
      }
    } else if (c == '{') {
      c = nextToken();
      if (c == '"') {
        skipPastEndQuote();
        if ((c = nextToken()) == ':') {
          return this;
        } else {
          throw reportError("skipObjField", "expected :, but " + c);
        }
      } else if (c == '}') { // empty object
        return null;
      } else {
        throw reportError("skipObjField", "expected \" after {");
      }
    } else if (c == '}') { // end of object
      return null;
    } else if (c == 'n') {
      skip(3);
      return null;
    } else {
      throw reportError("skipObjField", "expected [,{}n], but found: " + c);
    }
  }

  @Override
  public final JsonIterator closeObj() {
    final char c = nextToken();
    if (c == '}') {
      return this;
    } else {
      throw reportError("closeObj", "expected '}' but found: " + c);
    }
  }

  @Override
  public final void testObject(final FieldBufferPredicate fieldBufferFunction) {
    char c;
    for (int offset, len; ; ) {
      if ((c = nextToken()) == ',') {
        c = nextToken();
        if (c != '"') {
          throw reportError("testObject", "expected string field, but " + c);
        } else {
          offset = head;
          len = parse();
          if ((c = nextToken()) != ':') {
            throw reportError("testObject", "expected :, but " + c);
          } else if (breakOut(fieldBufferFunction, offset, len)) {
            return;
          }
        }
      } else if (c == '{') {
        c = nextToken();
        if (c == '"') {
          offset = head;
          len = parse();
          if ((c = nextToken()) != ':') {
            throw reportError("testObject", "expected :, but " + c);
          } else if (breakOut(fieldBufferFunction, offset, len)) {
            return;
          }
        } else if (c == '}') { // end of object
          return;
        } else {
          throw reportError("testObject", "expected \" after {");
        }
      } else if (c == '}') {
        return;
      } else if (c == 'n') {
        skip(3);
        return;
      } else {
        throw reportError("testObject", "expected [,{}n], but found: " + c);
      }
    }
  }

  abstract boolean breakOut(final FieldBufferPredicate fieldBufferFunction, final int offset, final int len);

  @Override
  public final <C> C testObject(final C context, final ContextFieldBufferPredicate<C> fieldBufferFunction) {
    char c;
    for (int offset, len; ; ) {
      if ((c = nextToken()) == ',') {
        c = nextToken();
        if (c != '"') {
          throw reportError("testObject", "expected string field, but " + c);
        }
        offset = head;
        len = parse();
        if ((c = nextToken()) != ':') {
          throw reportError("testObject", "expected :, but " + c);
        } else if (breakOut(context, fieldBufferFunction, offset, len)) {
          return context;
        }
      } else if (c == '{') {
        c = nextToken();
        if (c == '"') {
          offset = head;
          len = parse();
          if ((c = nextToken()) != ':') {
            throw reportError("testObject", "expected :, but " + c);
          } else if (breakOut(context, fieldBufferFunction, offset, len)) {
            return context;
          }
        } else if (c == '}') { // end of object
          return context;
        } else {
          throw reportError("testObject", "expected \" after {");
        }
      } else if (c == '}') {
        return context;
      } else if (c == 'n') {
        skip(3);
        return context;
      } else {
        throw reportError("testObject", "expected [,{}n], but found: " + c);
      }
    }
  }

  abstract <C> boolean breakOut(final C context,
                                final ContextFieldBufferPredicate<C> fieldBufferFunction,
                                final int offset, final int len);

  @Override
  public final <C> C testObject(final C context, final ContextFieldBufferMaskedPredicate<C> fieldBufferFunction) {
    char c;
    long mask = 0;
    for (int offset, len; ; ) {
      if ((c = nextToken()) == ',') {
        c = nextToken();
        if (c != '"') {
          throw reportError("testObject", "expected string field, but " + c);
        }
        offset = head;
        len = parse();
        if ((c = nextToken()) != ':') {
          throw reportError("testObject", "expected :, but " + c);
        } else if ((mask = test(context, mask, fieldBufferFunction, offset, len)) == BREAK_OUT) {
          return context;
        }
      } else if (c == '{') {
        c = nextToken();
        if (c == '"') {
          offset = head;
          len = parse();
          if ((c = nextToken()) != ':') {
            throw reportError("testObject", "expected :, but " + c);
          } else if ((mask = test(context, mask, fieldBufferFunction, offset, len)) == BREAK_OUT) {
            return context;
          }
        } else if (c == '}') { // end of object
          return context;
        } else {
          throw reportError("testObject", "expected \" after {");
        }
      } else if (c == '}') {
        return context;
      } else if (c == 'n') {
        skip(3);
        return context;
      } else {
        throw reportError("testObject", "expected [,{}n], but found: " + c);
      }
    }
  }

  abstract <C> long test(final C context,
                         final long mask,
                         final ContextFieldBufferMaskedPredicate<C> fieldBufferFunction,
                         final int offset, final int len);

  @Override
  public final <R> R applyObject(final FieldBufferFunction<R> fieldBufferFunction) {
    char c = nextToken();
    if (c == ',') {
      c = nextToken();
      if (c != '"') {
        throw reportError("applyObject", "expected string field, but " + c);
      } else {
        final int offset = head;
        final int len = parse();
        if ((c = nextToken()) != ':') {
          throw reportError("applyObject", "expected :, but " + c);
        } else {
          return apply(fieldBufferFunction, offset, len);
        }
      }
    } else if (c == '{') {
      c = nextToken();
      if (c == '"') {
        final int offset = head;
        final int len = parse();
        if ((c = nextToken()) != ':') {
          throw reportError("applyObject", "expected :, but " + c);
        } else {
          return apply(fieldBufferFunction, offset, len);
        }
      } else if (c == '}') { // end of object
        return null;
      } else {
        throw reportError("applyObject", "expected \" after {");
      }
    } else if (c == '}') {
      return null;
    } else if (c == 'n') {
      skip(3);
      return null;
    } else {
      throw reportError("applyObject", "expected [,{}n], but found: " + c);
    }
  }

  abstract <R> R apply(final FieldBufferFunction<R> fieldBufferFunction, final int offset, final int len);

  @Override
  public final <C, R> R applyObject(final C context, final ContextFieldBufferFunction<C, R> fieldBufferFunction) {
    char c = nextToken();
    if (c == ',') {
      c = nextToken();
      if (c != '"') {
        throw reportError("applyObject", "expected string field, but " + c);
      } else {
        final int offset = head;
        final int len = parse();
        if ((c = nextToken()) != ':') {
          throw reportError("applyObject", "expected :, but " + c);
        } else {
          return apply(context, fieldBufferFunction, offset, len);
        }
      }
    } else if (c == '{') {
      c = nextToken();
      if (c == '"') {
        final int offset = head;
        final int len = parse();
        if ((c = nextToken()) != ':') {
          throw reportError("applyObject", "expected :, but " + c);
        } else {
          return apply(context, fieldBufferFunction, offset, len);
        }
      } else if (c == '}') { // end of object
        return null;
      } else {
        throw reportError("applyObject", "expected \" after {");
      }
    } else if (c == '}') {
      return null;
    } else if (c == 'n') {
      skip(3);
      return null;
    } else {
      throw reportError("applyObject", "expected [,{}n], but found: " + c);
    }
  }

  abstract <C, R> R apply(final C context, final ContextFieldBufferFunction<C, R> fieldBufferFunction, final int offset, final int len);

  @Override
  public final double readDouble() {
    try {
      return Double.parseDouble(readNumberOrNumberString());
    } catch (final NumberFormatException e) {
      throw reportError("readDouble", e.toString());
    }
  }

  @Override
  public final float readFloat() {
    try {
      return Float.parseFloat(readNumberOrNumberString());
    } catch (final NumberFormatException e) {
      throw reportError("readFloat", e.toString());
    }
  }

  @Override
  public final BigDecimal readBigDecimal() {
    return readBigDecimal(READ_BIG_DECIMAL_FUNCTION);
  }

  @Override
  public final BigDecimal readBigDecimalDropZeroes() {
    return readBigDecimal(READ_BIG_DECIMAL_DROP_TRAILING_ZEROES_FUNCTION);
  }

  private BigDecimal readBigDecimal(final CharBufferFunction<BigDecimal> parseChars) {
    final var valueType = whatIsNext();
    if (valueType == STRING) {
      return applyChars(parseChars);
    } else if (valueType == NUMBER) {
      return parseBigDecimal(parseChars);
    } else if (valueType == NULL) {
      skip();
      return null;
    } else {
      throw reportError("readBigDecimal", "Must be a number, string or null but found " + valueType);
    }
  }

  private long readLongSlowPath(long value, final int scaleLimit) {
    boolean zero;
    if (value == 0) {
      zero = true;
    } else {
      zero = false;
      value = -value; // add negatives to avoid redundant checks for Long.MIN_VALUE on each iteration
    }
    int scale = 0;
    for (int i = head, ind; ; i++) {
      if (i == tail) {
        head = tail;
        break;
      }
      ind = peekIntDigitChar(i);
      if (ind == INVALID_CHAR_FOR_NUMBER) {
        head = i;
        break;
      } else if (!zero && value < -922337203685477580L) { // limit / 10
        throw reportError("readLongSlowPath", "value is too large for long");
      } else if (scale < scaleLimit) {
        if (zero) {
          value = -ind;
          zero = value == 0;
        } else {
          value = (value << 3) + (value << 1) - ind;
          if (value >= 0) {
            throw reportError("readLongSlowPath", "value is too large for long");
          }
        }
        ++scale;
      }
    }
    return zero ? 0 : scaleLong(value, scale, scaleLimit);
  }

  private long readUnscaledDigits(final long integer, final int scale) {
    final int mark = ++head;
    long unscaled = readLongSlowPath(integer, scale);
    if (head < tail) {
      final char c = peekChar(head);
      if (c == 'e' || c == 'E') {
        ++head;
        final int exponent = readInt();
        if (exponent < 0) {
          return reduceScale(unscaled, exponent);
        } else {
          final int mark2 = head;
          head = mark;
          unscaled = readLongSlowPath(integer, scale + exponent);
          head = mark2;
        }
      }
    }
    return unscaled;
  }

  @Override
  public final long readUnscaledAsLong(final int scale) {
    char c = nextToken();
    final var valueType = VALUE_TYPES[c];
    final boolean closeString = valueType == STRING;
    if (closeString) {
      c = nextToken();
    } else if (valueType != NUMBER) {
      throw reportError("readUnscaledAsLong", "Must be a number, string but found " + valueType);
    }
    final boolean negative;
    final long integer;
    if (c == '-') {
      negative = true;
      integer = readLong(readChar());
    } else {
      negative = false;
      integer = readLong(c);
      if (-integer == Long.MIN_VALUE) {
        throw reportError("readUnscaledAsLong", "value is too large for long");
      }
    }
    final long unscaled;
    if (head == tail) {
      if (integer == 0) {
        return 0L;
      }
      unscaled = scaleLong(-integer, 0, scale);
    } else {
      c = peekChar(head);
      if (c == '.') {
        unscaled = readUnscaledDigits(integer, scale);
      } else if (c == 'e' || c == 'E') {
        ++head;
        final int exponent = scale + readInt();
        unscaled = exponent < 0
            ? reduceScale(-integer, exponent)
            : scaleLong(-integer, 0, exponent);
      } else if (integer == 0) {
        if (closeString) {
          nextToken();
        }
        return 0L;
      } else {
        unscaled = scaleLong(-integer, 0, scale);
      }
    }
    if (closeString) {
      nextToken();
    }
    if (negative) {
      return unscaled;
    } else if (unscaled == Long.MIN_VALUE) {
      throw reportError("readUnscaledAsLong", "value is too large for long");
    } else {
      return -unscaled;
    }
  }

  private long reduceScale(long value, final int scaleLimit) {
    for (int scale = 0; scale-- > scaleLimit; ) {
      value /= 10;
    }
    return value;
  }

  private long scaleLong(long value, int scale, final int scaleLimit) {
    while (scale++ < scaleLimit) {
      if (value < -922337203685477580L) { // limit / 10
        throw reportError("readLongSlowPath", "value is too large for long");
      }
      value = (value << 3) + (value << 1);
      if (value >= 0) {
        throw reportError("readLongSlowPath", "value is too large for long");
      }
    }
    return value;
  }

  abstract BigDecimal parseBigDecimal(final CharBufferFunction<BigDecimal> parseChars);

  @Override
  public final BigInteger readBigInteger() {
    final var valueType = whatIsNext();
    if (valueType == NUMBER) {
      return new BigInteger(readNumberAsString());
    } else if (valueType == STRING) {
      return applyChars(READ_BIG_INTEGER_FUNCTION);
    } else if (valueType == NULL) {
      skip();
      return null;
    } else {
      throw reportError("readBigInteger", "Must be a number, string or null but found " + valueType);
    }
  }

  @Override
  public final Instant readDateTime() {
    return applyChars(InstantParser.INSTANT_PARSER);
  }

  @Override
  public String readNumberOrNumberString() {
    final var valueType = whatIsNext();
    if (valueType == NUMBER) {
      return readNumberAsString();
    } else if (valueType == STRING) {
      return readString();
    } else if (valueType == NULL) {
      skip();
      return null;
    } else {
      throw reportError("readNumberOrNumberString", "Must be a number, string or null but found " + valueType);
    }
  }

  @Override
  public final ValueType whatIsNext() {
    return VALUE_TYPES[peekToken()];
  }

  private void skipUntilBreak() {
    for (int i = head; ; i++) {
      if (i == tail) {
        head = tail;
        return;
      }
      switch (peekChar(i)) {
        case ' ', '\t', '\n', '\r', ',', '}', ']' -> {
          head = i;
          return;
        }
      }
    }
  }

  /// Skips past the closing character of the container whose opening character
  /// has already been consumed, tracking nesting `level` and skipping strings.
  void skipContainer(final char open, final char close, int level) {
    char c;
    for (int i = head; ; i++) {
      if (i == tail) {
        throw reportError("skipContainer", "incomplete " + (open == '{' ? "object" : "array"));
      }
      if ((c = peekChar(i)) == '"') { // If inside string, skip it
        head = i + 1;
        skipPastEndQuote();
        i = head - 1; // it will be i++ soon
      } else if (c == open) {
        level++;
      } else if (c == close) {
        // If we have returned to the original level, we're done
        if (--level == 0) {
          head = i + 1;
          return;
        }
      }
    }
  }

  @Override
  public final JsonIterator skip() {
    final char c = nextToken();
    switch (c) {
      case '"' -> skipPastEndQuote();
      case '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> skipUntilBreak();
      case 't', 'n' -> skip(3); // true or null
      case 'f' -> skip(4); // false
      case '[' -> skipContainer('[', ']', 1);
      case '{' -> skipContainer('{', '}', 1);
      default -> throw reportError("skip", "Cannot skip: " + c);
    }
    return this;
  }

  @Override
  public final boolean readNull() {
    final char c = peekToken();
    if (c == 'n') {
      skip(4); // null
      return true;
    } else {
      return false;
    }
  }

  @Override
  public final boolean readBoolean() {
    final char c = nextToken();
    if (c == 't') {
      skip(3); // true
      return true;
    } else if (c == 'f') {
      skip(4); // false
      return false;
    } else {
      throw reportError("readBoolean", "expected t or f, found: " + c);
    }
  }

  final void assertNotLeadingZero() {
    if (head == tail) {
      return;
    }
    final int peek = peekChar();
    if (peek < INT_DIGITS.length && INT_DIGITS[peek] != INVALID_CHAR_FOR_NUMBER) {
      throw reportError("assertNotLeadingZero", "leading zero is invalid");
    }
  }

  private int readIntSlowPath(int value) {
    value = -value; // add negatives to avoid redundant checks for Integer.MIN_VALUE on each iteration
    for (int i = head, ind; ; i++) {
      if (i == tail) {
        head = tail;
        return -value;
      }
      ind = peekIntDigitChar(i);
      if (ind == INVALID_CHAR_FOR_NUMBER) {
        head = i;
        return -value;
      } else if (value < -214748364) { // limit / 10
        throw reportError("readIntSlowPath", "value is too large for int");
      } else {
        value = (value << 3) + (value << 1) - ind;
        if (value >= 0) {
          throw reportError("readIntSlowPath", "value is too large for int");
        }
      }
    }
  }

  private int readInt(final char c) {
    final int ind = INT_DIGITS[c];
    if (ind == 0) {
      assertNotLeadingZero();
      return 0;
    } else if (ind == INVALID_CHAR_FOR_NUMBER) {
      throw reportError("readInt", "expected 0~9");
    } else if (tail - head > 9) {
      int i = head;
      final int ind2 = peekIntDigitChar(i);
      if (ind2 == INVALID_CHAR_FOR_NUMBER) {
        head = i;
        return ind;
      }
      final int ind3 = peekIntDigitChar(++i);
      if (ind3 == INVALID_CHAR_FOR_NUMBER) {
        head = i;
        return ind * 10 + ind2;
      }
      final int ind4 = peekIntDigitChar(++i);
      if (ind4 == INVALID_CHAR_FOR_NUMBER) {
        head = i;
        return ind * 100 + ind2 * 10 + ind3;
      }
      final int ind5 = peekIntDigitChar(++i);
      if (ind5 == INVALID_CHAR_FOR_NUMBER) {
        head = i;
        return ind * 1000 + ind2 * 100 + ind3 * 10 + ind4;
      }
      final int ind6 = peekIntDigitChar(++i);
      if (ind6 == INVALID_CHAR_FOR_NUMBER) {
        head = i;
        return ind * 10000 + ind2 * 1000 + ind3 * 100 + ind4 * 10 + ind5;
      }
      final int ind7 = peekIntDigitChar(++i);
      if (ind7 == INVALID_CHAR_FOR_NUMBER) {
        head = i;
        return ind * 100000 + ind2 * 10000 + ind3 * 1000 + ind4 * 100 + ind5 * 10 + ind6;
      }
      final int ind8 = peekIntDigitChar(++i);
      if (ind8 == INVALID_CHAR_FOR_NUMBER) {
        head = i;
        return ind * 1000000 + ind2 * 100000 + ind3 * 10000 + ind4 * 1000 + ind5 * 100 + ind6 * 10 + ind7;
      }
      final int result = ind * 10000000 + ind2 * 1000000 + ind3 * 100000 + ind4 * 10000 + ind5 * 1000 + ind6 * 100 + ind7 * 10 + ind8;
      head = ++i;
      if (peekIntDigitChar(i) == INVALID_CHAR_FOR_NUMBER) {
        return result;
      } else {
        return readIntSlowPath(result);
      }
    }
    return readIntSlowPath(ind);
  }

  @Override
  public final int readInt() {
    final char c = nextToken();
    if (c == '"') {
      final int val = readInt();
      if (nextToken() != '"') {
        throw reportError("readInt", "Lenient parsing of number string did not close with a quote.");
      }
      return val;
    } else if (c == '-') {
      return -readInt(readChar());
    } else {
      final int val = readInt(c);
      if (-val == Integer.MIN_VALUE) {
        throw reportError("readInt", "value is too large for int");
      } else {
        return val;
      }
    }
  }

  @Override
  public final short readShort() {
    final int v = readInt();
    if (Short.MIN_VALUE <= v && v <= Short.MAX_VALUE) {
      return (short) v;
    } else {
      throw reportError("readShort", "short overflow: " + v);
    }
  }

  private long readLongSlowPath(long value) {
    value = -value; // add negatives to avoid redundant checks for Long.MIN_VALUE on each iteration
    for (int i = head, ind; ; i++) {
      if (i == tail) {
        head = tail;
        return -value;
      }
      ind = peekIntDigitChar(i);
      if (ind == INVALID_CHAR_FOR_NUMBER) {
        head = i;
        return -value;
      } else if (value < -922337203685477580L) { // limit / 10
        throw reportError("readLongSlowPath", "value is too large for long");
      } else {
        value = (value << 3) + (value << 1) - ind;
        if (value >= 0) {
          throw reportError("readLongSlowPath", "value is too large for long");
        }
      }
    }
  }

  long readLong(final char c) {
    final long ind = INT_DIGITS[c];
    if (ind == 0) {
      assertNotLeadingZero();
      return 0;
    } else if (ind == INVALID_CHAR_FOR_NUMBER) {
      throw reportError("readLong", "expected 0~9");
    } else if (tail - head > 9) {
      int i = head;
      final int ind2 = peekIntDigitChar(i);
      if (ind2 == INVALID_CHAR_FOR_NUMBER) {
        head = i;
        return ind;
      }
      final int ind3 = peekIntDigitChar(++i);
      if (ind3 == INVALID_CHAR_FOR_NUMBER) {
        head = i;
        return ind * 10 + ind2;
      }
      final int ind4 = peekIntDigitChar(++i);
      if (ind4 == INVALID_CHAR_FOR_NUMBER) {
        head = i;
        return ind * 100 + ind2 * 10L + ind3;
      }
      final int ind5 = peekIntDigitChar(++i);
      if (ind5 == INVALID_CHAR_FOR_NUMBER) {
        head = i;
        return ind * 1000 + ind2 * 100L + ind3 * 10L + ind4;
      }
      final int ind6 = peekIntDigitChar(++i);
      if (ind6 == INVALID_CHAR_FOR_NUMBER) {
        head = i;
        return ind * 10000 + ind2 * 1000L + ind3 * 100L + ind4 * 10L + ind5;
      }
      final int ind7 = peekIntDigitChar(++i);
      if (ind7 == INVALID_CHAR_FOR_NUMBER) {
        head = i;
        return ind * 100000 + ind2 * 10000L + ind3 * 1000L + ind4 * 100L + ind5 * 10L + ind6;
      }
      final int ind8 = peekIntDigitChar(++i);
      if (ind8 == INVALID_CHAR_FOR_NUMBER) {
        head = i;
        return ind * 1000000 + ind2 * 100000L + ind3 * 10000L + ind4 * 1000L + ind5 * 100L + ind6 * 10L + ind7;
      }
      final long result = ind * 10000000 + ind2 * 1000000L + ind3 * 100000L + ind4 * 10000L + ind5 * 1000L + ind6 * 100L + ind7 * 10L + ind8;
      head = ++i;
      if (peekIntDigitChar(i) == INVALID_CHAR_FOR_NUMBER) {
        return result;
      } else {
        return readLongSlowPath(result);
      }
    }
    return readLongSlowPath(ind);
  }

  @Override
  public final long readLong() {
    final char c = nextToken();
    if (c == '"') {
      final long val = readLong();
      if (nextToken() != '"') {
        throw reportError("readLong", "Lenient parsing of number string did not close with a quote.");
      }
      return val;
    } else if (c == '-') {
      return -readLong(readChar());
    } else {
      final long val = readLong(c);
      if (-val == Long.MIN_VALUE) {
        throw reportError("readLong", "value is too large for long");
      } else {
        return val;
      }
    }
  }

  @Override
  public final boolean readArray() {
    final char c = nextToken();
    if (c == '[') {
      if (peekToken() == ']') {
        head++;
        return false;
      }
      return true;
    } else if (c == ',') {
      return true;
    } else if (c == ']') {
      return false;
    } else if (c == 'n') {
      skip(3); // null
      return false;
    } else {
      throw reportError("readArray", "expected [ or , or n or ], but found: " + c);
    }
  }

  @Override
  public final String readNumberAsString() {
    return parsedNumberAsString(parseNumber());
  }

  abstract int parseNumber();

  abstract String parsedNumberAsString(final int len);

  @Override
  public final <R> R applyNumberChars(final CharBufferFunction<R> applyChars) {
    return parseNumber(applyChars, parseNumber());
  }

  abstract <R> R parseNumber(final CharBufferFunction<R> applyChars, final int len);

  @Override
  public final <C, R> R applyNumberChars(final C context, final ContextCharBufferFunction<C, R> applyChars) {
    return parseNumber(context, applyChars, parseNumber());
  }

  abstract <C, R> R parseNumber(final C context,
                                final ContextCharBufferFunction<C, R> applyChars,
                                final int len);

  @Override
  public final int applyNumberCharsAsInt(final CharBufferToIntFunction applyChars) {
    return parseNumber(applyChars, parseNumber());
  }

  abstract int parseNumber(final CharBufferToIntFunction applyChars, final int len);

  @Override
  public final <C> int applyNumberCharsAsInt(final C context, final ContextCharBufferToIntFunction<C> applyChars) {
    return parseNumber(context, applyChars, parseNumber());
  }

  abstract <C> int parseNumber(final C context,
                               final ContextCharBufferToIntFunction<C> applyChars,
                               final int len);

  @Override
  public final long applyNumberCharsAsLong(final CharBufferToLongFunction applyChars) {
    return parseNumber(applyChars, parseNumber());
  }

  abstract long parseNumber(final CharBufferToLongFunction applyChars, final int len);

  @Override
  public final <C> long applyNumberCharsAsLong(final C context, final ContextCharBufferToLongFunction<C> applyChars) {
    return parseNumber(context, applyChars, parseNumber());
  }

  abstract <C> long parseNumber(final C context,
                                final ContextCharBufferToLongFunction<C> applyChars,
                                final int len);

  private static final class INIT_INT_DIGITS {

    private INIT_INT_DIGITS() {
    }

    private static int[] initIntDigits() {
      final int[] intDigits = new int[127];
      Arrays.fill(intDigits, INVALID_CHAR_FOR_NUMBER);
      for (int i = '0'; i <= '9'; ++i) {
        intDigits[i] = (i - '0');
      }
      return intDigits;
    }
  }
}
