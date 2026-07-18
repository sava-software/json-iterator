package systems.comodal.jsoniter;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

class BytesJsonIterator extends BaseJsonIterator {

  private static final VarHandle TO_LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
  private static final long QUOTE_PATTERN = JIUtil.compileReplacePattern((byte) '"');
  private static final long ESCAPE_PATTERN = JIUtil.compileReplacePattern((byte) ('\\' & 0xFF));
  private static final long HIGH_BITS = 0x8080808080808080L;

  byte[] buf;
  private char[] charBuf;
  // Field name span for the matcher hook: buf itself on the zero-copy fast
  // path, or a decoded UTF-8 array for escaped names.
  private byte[] fieldBuf;
  private int fieldOffset;

  BytesJsonIterator(final byte[] buf, final int head, final int tail) {
    this(buf, head, tail, 64);
  }

  BytesJsonIterator(final byte[] buf, final int head, final int tail, final int charBufferLength) {
    super(head, tail);
    this.buf = buf;
    this.charBuf = new char[charBufferLength];
  }

  private static long matchPattern(final long input) {
    // https://richardstartin.github.io/posts/finding-bytes
    // Hacker's Delight ch. 6: https://books.google.com/books?id=VicPJYM0I5QC&lpg=PP1&pg=PA117#v=onepage&q&f=false
    return ~(((input & 0x7F7F7F7F7F7F7F7FL) + 0x7F7F7F7F7F7F7F7FL) | input | 0x7F7F7F7F7F7F7F7FL);
  }

  private static boolean containsPattern(final long input) {
    return matchPattern(input) != 0;
  }

  private static long matchQuotePattern(final long word) {
    return matchPattern(word ^ BytesJsonIterator.QUOTE_PATTERN);
  }

  private static boolean containsMultiByteOrEscapePattern(final long word) {
    // A multi-byte UTF-8 byte is any byte with its high bit set, not the exact
    // byte 0x80: an XOR/zero-byte match here let the word loops in parseString
    // and skipPastEndQuote hop through multi-byte content and hand off to the
    // byte-accurate parsers mid-character.
    return (word & HIGH_BITS) != 0 || containsPattern(word ^ ESCAPE_PATTERN);
  }

  @Override
  public JsonIterator reset(final byte[] buf) {
    this.buf = buf;
    this.head = 0;
    this.tail = buf.length;
    return this;
  }

  @Override
  public JsonIterator reset(final byte[] buf, final int head, final int tail) {
    this.buf = buf;
    this.head = head;
    this.tail = tail;
    return this;
  }

  @Override
  public JsonIterator reset(final char[] buf) {
    return reset(buf, 0, buf.length);
  }

  @Override
  public JsonIterator reset(final char[] buf, final int head, final int tail) {
    return new CharsJsonIterator(buf, head, tail);
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
  final String getBufferString(final int from, final int to) {
    return new String(buf, from, Math.min(to, tail) - from);
  }

  @Override
  final char nextToken() {
    byte c;
    for (int i = head; ; ) {
      if (i == tail) {
        throw reportError("nextToken", "unexpected end");
      }
      c = buf[i++];
      // Multibyte UTF-8 bytes are negative and fall through as tokens.
      if (c != ' ' && c != '\n' && c != '\t' && c != '\r') {
        head = i;
        return (char) (c & 0xff);
      }
    }
  }

  @Override
  final char peekToken() {
    byte c;
    for (int i = head; ; i++) {
      if (i == tail) {
        throw reportError("peekToken", "unexpected end");
      }
      c = buf[i];
      if (c != ' ' && c != '\n' && c != '\t' && c != '\r') {
        head = i;
        return (char) (c & 0xff);
      }
    }
  }

  byte read() {
    return buf[head++];
  }

  @Override
  final char readChar() {
    if (head == tail) {
      throw reportError("readChar", "unexpected end");
    }
    return (char) (read() & 0xff);
  }

  @Override
  final char peekChar() {
    if (head == tail) {
      throw reportError("peekChar", "unexpected end");
    }
    return (char) (buf[head] & 0xff);
  }

  @Override
  final char peekChar(final int offset) {
    return (char) (buf[offset] & 0xff);
  }

  @Override
  final int peekIntDigitChar(final int offset) {
    return intDigit(buf[offset]);
  }

  private void doubleReusableCharBuffer() {
    final char[] newBuf = new char[charBuf.length << 1];
    System.arraycopy(charBuf, 0, newBuf, 0, charBuf.length);
    charBuf = newBuf;
  }

  @Override
  final int parse() {
    // Scan word-at-a-time for the closing quote, then widen the whole ascii
    // run into charBuf with a branch-free copy the JIT can vectorize. Escapes
    // and multibyte characters hand off to the byte-accurate parser after
    // bulk-copying the clean prefix.
    int i = head;
    for (int nextOffset = i + Long.BYTES; nextOffset <= tail; ) {
      final long word = (long) TO_LONG.get(buf, i);
      if (containsMultiByteOrEscapePattern(word)) {
        break;
      }
      final long tmp = matchQuotePattern(word);
      if (tmp != 0) {
        final int quote = i + ((Long.numberOfTrailingZeros(tmp << 1) >>> 3) - 1);
        final int len = widenToCharBuf(quote);
        head = quote + 1;
        return len;
      }
      i = nextOffset;
      nextOffset += Long.BYTES;
    }
    final int j = widenToCharBuf(i);
    head = i;
    return parseTail(j);
  }

  /// Copies `[head, to)` into the front of charBuf, widening each byte.
  private int widenToCharBuf(final int to) {
    final int from = head;
    final int len = to - from;
    if (len > charBuf.length) {
      charBuf = new char[Math.max(len, charBuf.length << 1)];
    }
    for (int k = 0; k < len; ++k) {
      charBuf[k] = (char) (buf[from + k] & 0xff);
    }
    return len;
  }

  private int parseTail(int j) {
    byte c;
    while (head < tail) {
      c = buf[head];
      if (c == '"') {
        head++;
        return j;
      } else if ((c ^ '\\') < 1) {
        // If a backslash is encountered, which is a beginning of an escape sequence
        // or a high bit was set - indicating an UTF-8 encoded multi-byte character,
        // there is no chance that we can decode the string without instantiating
        // a temporary buffer, so quit this loop.
        return parseMultiByteString(j);
      } else {
        head++;
        if (j == charBuf.length) {
          doubleReusableCharBuffer();
        }
        charBuf[j++] = (char) (c & 0xff);
      }
    }
    throw reportError("parse", "incomplete string");
  }

  final void skipPastSingleByteEndQuote() {
    for (byte c; head < tail; head++) {
      c = buf[head];
      if (c == '"') {
        head++;
        return;
      } else if ((c ^ '\\') < 1) {
        skipPastMultiByteEndQuote();
        return;
      }
    }
    throw reportError("skipPastSingleByteEndQuote", "incomplete string");
  }

  @Override
  final void skipPastEndQuote() {
    int nextOffset = head + Long.BYTES;
    if (nextOffset > tail) {
      skipPastSingleByteEndQuote();
    } else {
      for (long word, tmp; ; ) {
        word = (long) TO_LONG.get(buf, head);
        if (containsMultiByteOrEscapePattern(word)) {
          skipPastMultiByteEndQuote();
          return;
        } else {
          tmp = matchQuotePattern(word);
          if (tmp != 0) {
            head += (Long.numberOfTrailingZeros(tmp << 1) >>> 3);
            return;
          } else {
            head = nextOffset;
            nextOffset += Long.BYTES;
            if (nextOffset > tail) {
              if (head < tail) {
                head = tail - Long.BYTES;
              } else {
                throw reportError("skipPastEndQuote", "incomplete string");
              }
            }
          }
        }
      }
    }
  }

  private static byte[] decodeBase64(final byte[] buf, final int from, final int to) {
    // Decode directly from the source buffer. The decoder sizes its output
    // exactly for valid padded input, so the trimming copy is rare.
    final var decoded = Base64.getDecoder().decode(ByteBuffer.wrap(buf, from, to - from));
    final byte[] data = decoded.array();
    return decoded.limit() == data.length ? data : Arrays.copyOf(data, decoded.limit());
  }

  @Override
  public byte[] decodeBase64String() {
    final char c = nextToken();
    if (c == '"') {
      return parseBase64String();
    } else if (c == 'n') {
      skipNull();
      return null;
    } else {
      throw reportError("decodeBase64String", "expected string or null, but " + c);
    }
  }

  /// Fallback when the value may contain escapes (e.g. "\/" — '/' is in the
  /// base64 alphabet and some encoders escape it): parse() unescapes into
  /// charBuf, then the chars narrow to the ascii the decoder needs.
  private byte[] parseEscapedBase64String() {
    final int len = parse();
    final byte[] ascii = new byte[len];
    for (int i = 0; i < len; ++i) {
      ascii[i] = (byte) charBuf[i];
    }
    return Base64.getDecoder().decode(ascii);
  }

  final byte[] parseBase64String() {
    int nextOffset = head + Long.BYTES;
    if (nextOffset > tail) {
      return parseEscapedBase64String();
    } else {
      long word, tmp;
      for (int i = head; ; ) {
        word = (long) TO_LONG.get(buf, i);
        if (containsMultiByteOrEscapePattern(word)) {
          return parseEscapedBase64String();
        }
        tmp = matchQuotePattern(word);
        if (tmp != 0) {
          i += (Long.numberOfTrailingZeros(tmp << 1) >>> 3);
          final int to = i - 1;
          final var data = decodeBase64(buf, head, to);
          head = i;
          return data;
        } else {
          i = nextOffset;
          nextOffset += Long.BYTES;
          if (nextOffset > tail) {
            if (i < tail) {
              i = tail - Long.BYTES; // push i back a bit to match 8 byte pattern length.
            } else {
              throw reportError("decodeBase64String", "incomplete string");
            }
          }
        }
      }
    }
  }

  @Override
  protected final String parseString() {
    int nextOffset = head + Long.BYTES;
    if (nextOffset > tail) {
      final int len = parse();
      return new String(charBuf, 0, len);
    } else {
      long word, tmp;
      for (int i = head; ; ) {
        word = (long) TO_LONG.get(buf, i);
        if (containsMultiByteOrEscapePattern(word)) {
          final int len = parseMultiByteString(0);
          return new String(charBuf, 0, len);
        } else {
          tmp = matchQuotePattern(word);
          if (tmp != 0) {
            i += (Long.numberOfTrailingZeros(tmp << 1) >>> 3);
            final var str = new String(buf, head, (i - 1) - head, StandardCharsets.US_ASCII);
            head = i;
            return str;
          } else {
            i = nextOffset;
            nextOffset += Long.BYTES;
            if (nextOffset > tail) {
              if (i < tail) {
                i = tail - Long.BYTES; // push i back a bit to match 8 byte pattern length.
              } else {
                throw reportError("parseString", "incomplete string");
              }
            }
          }
        }
      }
    }
  }

  @Override
  final <R> R parse(final CharBufferFunction<R> applyChars) {
    final int len = parse();
    return applyChars.apply(charBuf, 0, len);
  }

  @Override
  final <C, R> R parse(final C context, final ContextCharBufferFunction<C, R> applyChars) {
    final int len = parse();
    return applyChars.apply(context, charBuf, 0, len);
  }

  @Override
  final int parse(final CharBufferToIntFunction applyChars) {
    final int len = parse();
    return applyChars.applyAsInt(charBuf, 0, len);
  }

  @Override
  final double parse(final CharBufferToDoubleFunction applyChars) {
    final int len = parse();
    return applyChars.applyAsDouble(charBuf, 0, len);
  }

  @Override
  final <C> int parse(final C context, final ContextCharBufferToIntFunction<C> applyChars) {
    final int len = parse();
    return applyChars.applyAsInt(context, charBuf, 0, len);
  }

  @Override
  final long parse(final CharBufferToLongFunction applyChars) {
    final int len = parse();
    return applyChars.applyAsLong(charBuf, 0, len);
  }

  @Override
  final <C> long parse(final C context, final ContextCharBufferToLongFunction<C> applyChars) {
    final int len = parse();
    return applyChars.applyAsLong(context, charBuf, 0, len);
  }

  @Override
  final boolean parse(final CharBufferPredicate testChars) {
    final int len = parse();
    return testChars.apply(charBuf, 0, len);
  }

  @Override
  final <C> boolean parse(final C context, final ContextCharBufferPredicate<C> testChars) {
    final int len = parse();
    return testChars.apply(context, charBuf, 0, len);
  }

  @Override
  final void parse(final CharBufferConsumer testChars) {
    final int len = parse();
    testChars.accept(charBuf, 0, len);
  }

  @Override
  final <C> void parse(final C context, final ContextCharBufferConsumer<C> testChars) {
    final int len = parse();
    testChars.accept(context, charBuf, 0, len);
  }

  @Override
  final boolean parseFieldEquals(final String field) {
    final int fieldLength = field.length();
    int i = head;
    for (int f = 0; f < fieldLength; ++f, ++i) {
      if (i >= tail) {
        return parseFieldEqualsSlow(field); // reports the incomplete string
      }
      final byte b = buf[i];
      if (b == '\\' || b < 0) {
        // Escaped or multi-byte field names take the unescaping slow path.
        return parseFieldEqualsSlow(field);
      } else if ((char) (b & 0xff) != field.charAt(f)) {
        skipPastEndQuote();
        return false;
      }
    }
    if (i == tail) {
      // The buffer ended exactly after the matched prefix: parse to report
      // the incomplete string.
      return parseFieldEqualsSlow(field);
    }
    if (buf[i] == '"') {
      head = i + 1;
      return true;
    }
    // The name continues past the compared prefix.
    skipPastEndQuote();
    return false;
  }

  private boolean parseFieldEqualsSlow(final String field) {
    final int len = parse();
    return JsonIterator.fieldEquals(field, charBuf, 0, len);
  }

  @Override
  final int parseFieldName() {
    // Scan word-at-a-time for the closing quote; a clean ascii name is
    // returned as a span of buf with no copy. Escapes and multi-byte
    // characters fall through to the byte-accurate loop below.
    int i = head;
    for (int nextOffset = i + Long.BYTES; nextOffset <= tail; ) {
      final long word = (long) TO_LONG.get(buf, i);
      if (containsMultiByteOrEscapePattern(word)) {
        break;
      }
      final long tmp = matchQuotePattern(word);
      if (tmp != 0) {
        final int quote = i + ((Long.numberOfTrailingZeros(tmp << 1) >>> 3) - 1);
        fieldBuf = buf;
        fieldOffset = head;
        final int len = quote - head;
        head = quote + 1;
        return len;
      }
      i = nextOffset;
      nextOffset += Long.BYTES;
    }
    for (byte c; i < tail; ++i) {
      c = buf[i];
      if (c == '"') {
        fieldBuf = buf;
        fieldOffset = head;
        final int len = i - head;
        head = i + 1;
        return len;
      } else if ((c ^ '\\') < 1) { // escape or multi-byte
        return parseFieldNameSlow();
      }
    }
    throw reportError("parseFieldName", "incomplete string");
  }

  /// Escaped or multi-byte field names are rare: unescape into charBuf, then
  /// re-encode so the matcher always sees the decoded name's UTF-8 bytes.
  private int parseFieldNameSlow() {
    final int len = parse();
    final byte[] utf8 = new String(charBuf, 0, len).getBytes(StandardCharsets.UTF_8);
    fieldBuf = utf8;
    fieldOffset = 0;
    return utf8.length;
  }

  @Override
  final int matchField(final FieldMatcher matcher, final int len) {
    return matcher.match(fieldBuf, fieldOffset, len);
  }

  @Override
  final boolean breakOut(final FieldBufferPredicate fieldBufferFunction, final int offset, final int len) {
    return !fieldBufferFunction.test(charBuf, 0, len, this);
  }

  @Override
  final <C> boolean breakOut(final C context, final ContextFieldBufferPredicate<C> fieldBufferFunction, final int offset, final int len) {
    return !fieldBufferFunction.test(context, charBuf, 0, len, this);
  }

  @Override
  final <R> R apply(final FieldBufferFunction<R> fieldBufferFunction, final int offset, final int len) {
    return fieldBufferFunction.apply(charBuf, 0, len, this);
  }

  @Override
  final <C, R> R apply(final C context, final ContextFieldBufferFunction<C, R> fieldBufferFunction, final int offset, final int len) {
    return fieldBufferFunction.apply(context, charBuf, 0, len, this);
  }

  @Override
  final BigDecimal parseBigDecimal(final CharBufferFunction<BigDecimal> parseChars) {
    return parseChars.apply(charBuf, 0, parseNumber());
  }

  private int parseMultiByteString(int j) {
    boolean isExpectingLowSurrogate = false;
    for (int bc; head < tail; ) {
      bc = buf[head++];
      if (bc == '"') {
        return j;
      } else if (bc == '\\') {
        if (head == tail) {
          break;
        }
        bc = buf[head++];
        switch (bc) {
          case 'b':
            bc = '\b';
            break;
          case 't':
            bc = '\t';
            break;
          case 'n':
            bc = '\n';
            break;
          case 'f':
            bc = '\f';
            break;
          case 'r':
            bc = '\r';
            break;
          case '"':
          case '/':
          case '\\':
            break;
          case 'u':
            if (head == tail) {
              throw reportError("parseMultiByteString", "incomplete string");
            }
            bc = (JHex.decode(buf[head++]) << 12);
            if (head == tail) {
              throw reportError("parseMultiByteString", "incomplete string");
            }
            bc += (JHex.decode(buf[head++]) << 8);
            if (head == tail) {
              throw reportError("parseMultiByteString", "incomplete string");
            }
            bc += (JHex.decode(buf[head++]) << 4);
            if (head == tail) {
              throw reportError("parseMultiByteString", "incomplete string");
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
            break;
          default:
            throw reportError("parseMultiByteString", "invalid escape character: " + bc);
        }
      } else if ((bc & 0x80) != 0) {
        if (head == tail) {
          break;
        }
        final int u2 = buf[head++];
        if ((bc & 0xE0) == 0xC0) {
          bc = ((bc & 0x1F) << 6) + (u2 & 0x3F);
        } else {
          if (head == tail) {
            break;
          }
          final int u3 = buf[head++];
          if ((bc & 0xF0) == 0xE0) {
            bc = ((bc & 0x0F) << 12) + ((u2 & 0x3F) << 6) + (u3 & 0x3F);
          } else {
            if (head == tail) {
              break;
            }
            final int u4 = buf[head++];
            if ((bc & 0xF8) == 0xF0) {
              bc = ((bc & 0x07) << 18) + ((u2 & 0x3F) << 12) + ((u3 & 0x3F) << 6) + (u4 & 0x3F);
            } else {
              throw reportError("parseMultiByteString", "invalid unicode character");
            }
            if (bc >= 0x10000) {
              // check if valid unicode
              if (bc >= 0x110000) {
                throw reportError("parseMultiByteString", "invalid unicode character");
              }
              // split surrogates
              final int sup = bc - 0x10000;
              if (charBuf.length == j) {
                doubleReusableCharBuffer();
              }
              charBuf[j++] = (char) ((sup >>> 10) + 0xd800);
              if (charBuf.length == j) {
                doubleReusableCharBuffer();
              }
              charBuf[j++] = (char) ((sup & 0x3ff) + 0xdc00);
              continue;
            }
          }
        }
      }
      if (charBuf.length == j) {
        doubleReusableCharBuffer();
      }
      charBuf[j++] = (char) bc;
    }
    throw reportError("parseMultiByteString", "incomplete string");
  }

  private void skipPastMultiByteEndQuote() {
    boolean isExpectingLowSurrogate = false;
    for (int bc; head < tail; ) {
      bc = buf[head++];
      if (bc == '"') {
        return;
      } else if (bc == '\\') {
        if (head == tail) {
          break;
        }
        bc = buf[head++];
        switch (bc) {
          case 'b':
          case 't':
          case 'n':
          case 'f':
          case 'r':
          case '"':
          case '/':
          case '\\':
            break;
          case 'u':
            if (head == tail) {
              throw reportError("skipPastMultiByteEndQuote", "incomplete string");
            }
            bc = (JHex.decode(buf[head++]) << 12);
            if (head == tail) {
              throw reportError("skipPastMultiByteEndQuote", "incomplete string");
            }
            bc += (JHex.decode(buf[head++]) << 8);
            if (head == tail) {
              throw reportError("skipPastMultiByteEndQuote", "incomplete string");
            }
            bc += (JHex.decode(buf[head++]) << 4);
            if (head == tail) {
              throw reportError("skipPastMultiByteEndQuote", "incomplete string");
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
            break;
          default:
            throw reportError("skipPastMultiByteEndQuote", "invalid escape character: " + bc);
        }
      } else if ((bc & 0x80) != 0) {
        if (head == tail) {
          break;
        }
        final int u2 = buf[head++];
        if ((bc & 0xE0) != 0xC0) {
          if (head == tail) {
            break;
          }
          final int u3 = buf[head++];
          if ((bc & 0xF0) != 0xE0) {
            if (head == tail) {
              break;
            }
            final int u4 = buf[head++];
            if ((bc & 0xF8) == 0xF0) {
              bc = ((bc & 0x07) << 18) + ((u2 & 0x3F) << 12) + ((u3 & 0x3F) << 6) + (u4 & 0x3F);
            } else {
              throw reportError("skipPastMultiByteEndQuote", "invalid unicode character");
            }
            if (bc >= 0x10000) {
              // check if valid unicode
              if (bc >= 0x110000) {
                throw reportError("skipPastMultiByteEndQuote", "invalid unicode character");
              }
            }
          }
        }
      }
    }
    throw reportError("skipPastMultiByteEndQuote", "incomplete string");
  }

  @Override
  final String parsedNumberAsString(final int len) {
    return new String(charBuf, 0, len);
  }

  @Override
  final <R> R parseNumber(final CharBufferFunction<R> applyChars, final int len) {
    return applyChars.apply(charBuf, 0, len);
  }

  @Override
  final <C, R> R parseNumber(final C context,
                             final ContextCharBufferFunction<C, R> applyChars,
                             final int len) {
    return applyChars.apply(context, charBuf, 0, len);
  }

  @Override
  final int parseNumber(final CharBufferToIntFunction applyChars, final int len) {
    return applyChars.applyAsInt(charBuf, 0, len);
  }

  @Override
  final <C> int parseNumber(final C context, final ContextCharBufferToIntFunction<C> applyChars, final int len) {
    return applyChars.applyAsInt(context, charBuf, 0, len);
  }

  @Override
  final long parseNumber(final CharBufferToLongFunction applyChars, final int len) {
    return applyChars.applyAsLong(charBuf, 0, len);
  }

  @Override
  final double parseNumber(final CharBufferToDoubleFunction applyChars, final int len) {
    return applyChars.applyAsDouble(charBuf, 0, len);
  }

  @Override
  final <C> long parseNumber(final C context, final ContextCharBufferToLongFunction<C> applyChars, final int len) {
    return applyChars.applyAsLong(context, charBuf, 0, len);
  }

  @Override
  final int parseNumber() {
    char c;
    for (int i = head, len = 0; ; i++) {
      if (i == tail) {
        head = tail;
        return len;
      }
      if (len == charBuf.length) {
        doubleReusableCharBuffer();
      }
      switch ((c = peekChar(i))) {
        // entered without a peekToken from the applyNumberChars family, so
        // leading whitespace is this scan's job — but past the first token
        // char whitespace terminates, it must not splice "1 2" into "12"
        case ' ', '\t', '\n', '\r' -> {
          if (len != 0) {
            head = i;
            return len;
          }
        }
        case '.', 'e', 'E', '-', '+', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> charBuf[len++] = c;
        default -> {
          head = i;
          return len;
        }
      }
    }
  }
}
