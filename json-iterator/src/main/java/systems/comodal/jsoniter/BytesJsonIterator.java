package systems.comodal.jsoniter;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorOperators;

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

  private static final byte QUOTE = '"';
  private static final byte BACKSLASH = '\\';

  // Real documents are dominated by short strings, and on hardware where
  // vector-mask extraction is expensive (e.g. 128-bit NEON) the vector scan
  // loops pay that cost on the very first chunk of every short string. The
  // string scans below therefore SWAR the first few words and only switch to
  // vector chunks for longer runs.
  private static final int SWAR_PREFIX = 32;

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
  public IndexedJsonIterator index() {
    return new IndexedBytesJsonIterator(buf, head, tail, false);
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
  String getBufferString(final int from, final int to) {
    return new String(buf, from, Math.min(to, tail) - from);
  }

  @Override
  char nextToken() {
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
  char peekToken() {
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
  char readChar() {
    return (char) (read() & 0xff);
  }

  @Override
  char peekChar() {
    return (char) (buf[head] & 0xff);
  }

  @Override
  char peekChar(final int offset) {
    return (char) (buf[offset] & 0xff);
  }

  @Override
  int peekIntDigitChar(final int offset) {
    return INT_DIGITS[buf[offset]];
  }

  private void doubleReusableCharBuffer() {
    final char[] newBuf = new char[charBuf.length << 1];
    System.arraycopy(charBuf, 0, newBuf, 0, charBuf.length);
    charBuf = newBuf;
  }

  private void ensureCharBufCapacity(final int capacity) {
    if (charBuf.length < capacity) {
      int newLength = charBuf.length << 1;
      while (newLength < capacity) {
        newLength <<= 1;
      }
      final char[] newBuf = new char[newLength];
      System.arraycopy(charBuf, 0, newBuf, 0, charBuf.length);
      charBuf = newBuf;
    }
  }

  /// Widens one full vector of ASCII bytes at `head` into charBuf at `j`.
  private void widenToCharBuf(final ByteVector chunk, final int j) {
    ((ShortVector) chunk.convertShape(VectorOperators.B2S, VectorSupport.SHORT_SPECIES, 0)).intoCharArray(charBuf, j);
    ((ShortVector) chunk.convertShape(VectorOperators.B2S, VectorSupport.SHORT_SPECIES, 1)).intoCharArray(charBuf, j + (VectorSupport.BYTE_LANES >> 1));
  }

  @Override
  int parse() {
    int j = 0;
    int h = head;
    boolean special = false;
    for (final int prefixEnd = h + SWAR_PREFIX; h + Long.BYTES <= tail && h < prefixEnd; ) {
      final long word = (long) TO_LONG.get(buf, h);
      if (containsMultiByteOrEscapePattern(word)) {
        special = true;
        break;
      }
      final long quote = matchPattern(word ^ QUOTE_PATTERN);
      if (quote != 0) {
        final int pos = h + (Long.numberOfTrailingZeros(quote) >>> 3);
        final int len = pos - head;
        ensureCharBufCapacity(len);
        for (int k = 0, from = head; k < len; ++k) {
          charBuf[k] = (char) (buf[from + k] & 0xff);
        }
        head = pos + 1;
        return len;
      }
      h += Long.BYTES;
    }
    if (h > head) {
      j = h - head;
      ensureCharBufCapacity(j);
      for (int k = 0, from = head; k < j; ++k) {
        charBuf[k] = (char) (buf[from + k] & 0xff);
      }
    }
    final int lanes = VectorSupport.BYTE_LANES;
    while (!special && h + lanes <= tail) {
      final var chunk = ByteVector.fromArray(VectorSupport.BYTE_SPECIES, buf, h);
      final var stopMask = chunk.eq(QUOTE).or(chunk.eq(BACKSLASH)).or(chunk.compare(VectorOperators.LT, (byte) 0));
      // Lane reductions (anyTrue/firstTrue) are much cheaper than mask.toLong
      // bit extraction on NEON; the byte at the first marked lane identifies
      // which stop was hit, so the bitset is never needed.
      if (!stopMask.anyTrue()) {
        ensureCharBufCapacity(j + lanes);
        widenToCharBuf(chunk, j);
        j += lanes;
        h += lanes;
      } else {
        final int n = stopMask.firstTrue();
        ensureCharBufCapacity(j + n);
        for (int i = 0; i < n; ++i) {
          charBuf[j + i] = (char) buf[h + i];
        }
        j += n;
        if (buf[h + n] == QUOTE) {
          head = h + n + 1;
          return j;
        }
        // An escape sequence or a UTF-8 multi-byte character requires the scalar decoder.
        head = h + n;
        return parseMultiByteString(j);
      }
    }
    head = h;
    byte c;
    while (head < tail) {
      c = buf[head];
      if (c == '"') {
        head++;
        return j;
      } else if ((c ^ '\\') < 1) {
        // Backslash (escape sequence) or high bit set (UTF-8 multi-byte character).
        return parseMultiByteString(j);
      }
      head++;
      if (j == charBuf.length) {
        doubleReusableCharBuffer();
      }
      charBuf[j++] = (char) (c & 0xff);
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
  void skipPastEndQuote() {
    for (final int prefixEnd = head + SWAR_PREFIX; head + Long.BYTES <= tail && head < prefixEnd; ) {
      final long word = (long) TO_LONG.get(buf, head);
      if (containsMultiByteOrEscapePattern(word)) {
        skipPastMultiByteEndQuote();
        return;
      }
      final long quote = matchPattern(word ^ QUOTE_PATTERN);
      if (quote != 0) {
        head += (Long.numberOfTrailingZeros(quote) >>> 3) + 1;
        return;
      }
      head += Long.BYTES;
    }
    final int lanes = VectorSupport.BYTE_LANES;
    int nextOffset = head + lanes;
    if (nextOffset > tail) {
      skipPastSingleByteEndQuote();
      return;
    }
    for (; ; ) {
      final var chunk = ByteVector.fromArray(VectorSupport.BYTE_SPECIES, buf, head);
      // Escapes need validation and multi-byte characters need decoding awareness,
      // so either defers to the scalar path when encountered before the end quote.
      final var stopMask = chunk.eq(QUOTE).or(chunk.eq(BACKSLASH)).or(chunk.compare(VectorOperators.LT, (byte) 0));
      if (!stopMask.anyTrue()) {
        head = nextOffset;
        nextOffset += lanes;
        if (nextOffset > tail) {
          if (head < tail) {
            head = tail - lanes;
          } else {
            throw reportError("skipPastEndQuote", "incomplete string");
          }
        }
      } else {
        final int n = stopMask.firstTrue();
        if (buf[head + n] == QUOTE) {
          head += n + 1;
        } else {
          skipPastMultiByteEndQuote();
        }
        return;
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

  byte[] parseBase64String() {
    final int lanes = VectorSupport.BYTE_LANES;
    int nextOffset = head + lanes;
    if (nextOffset > tail) {
      return parseEscapedBase64String();
    }
    for (int i = head; ; ) {
      final var chunk = ByteVector.fromArray(VectorSupport.BYTE_SPECIES, buf, i);
      if (chunk.eq(BACKSLASH).or(chunk.compare(VectorOperators.LT, (byte) 0)).anyTrue()) {
        return parseEscapedBase64String();
      }
      final var quoteMask = chunk.eq(QUOTE);
      if (quoteMask.anyTrue()) {
        i += Long.numberOfTrailingZeros(quoteMask.toLong());
        final var data = decodeBase64(buf, head, i);
        head = i + 1;
        return data;
      }
      i = nextOffset;
      nextOffset += lanes;
      if (nextOffset > tail) {
        if (i < tail) {
          i = tail - lanes; // push i back a bit to match the vector length.
        } else {
          throw reportError("decodeBase64String", "incomplete string");
        }
      }
    }
  }

  @Override
  protected String parseString() {
    int h = head;
    for (final int prefixEnd = h + SWAR_PREFIX; h + Long.BYTES <= tail && h < prefixEnd; ) {
      final long word = (long) TO_LONG.get(buf, h);
      if (containsMultiByteOrEscapePattern(word)) {
        final int len = parseMultiByteString(0);
        return new String(charBuf, 0, len);
      }
      final long quote = matchPattern(word ^ QUOTE_PATTERN);
      if (quote != 0) {
        final int pos = h + (Long.numberOfTrailingZeros(quote) >>> 3);
        final var str = new String(buf, head, pos - head, StandardCharsets.US_ASCII);
        head = pos + 1;
        return str;
      }
      h += Long.BYTES;
    }
    final int lanes = VectorSupport.BYTE_LANES;
    int nextOffset = h + lanes;
    if (nextOffset > tail) {
      final int len = parse();
      return new String(charBuf, 0, len);
    }
    for (int i = h; ; ) {
      final var chunk = ByteVector.fromArray(VectorSupport.BYTE_SPECIES, buf, i);
      final var stopMask = chunk.eq(QUOTE).or(chunk.eq(BACKSLASH)).or(chunk.compare(VectorOperators.LT, (byte) 0));
      if (!stopMask.anyTrue()) {
        i = nextOffset;
        nextOffset += lanes;
        if (nextOffset > tail) {
          if (i < tail) {
            i = tail - lanes; // push i back a bit to match the vector length.
          } else {
            throw reportError("parseString", "incomplete string");
          }
        }
      } else {
        final int n = stopMask.firstTrue();
        if (buf[i + n] == QUOTE) {
          final var str = new String(buf, head, (i + n) - head, StandardCharsets.US_ASCII);
          head = i + n + 1;
          return str;
        }
        // An escape sequence or a UTF-8 multi-byte character requires the scalar decoder.
        final int len = parseMultiByteString(0);
        return new String(charBuf, 0, len);
      }
    }
  }

  @Override
  <R> R parse(final CharBufferFunction<R> applyChars) {
    final int len = parse();
    return applyChars.apply(charBuf, 0, len);
  }

  @Override
  <C, R> R parse(final C context, final ContextCharBufferFunction<C, R> applyChars) {
    final int len = parse();
    return applyChars.apply(context, charBuf, 0, len);
  }

  @Override
  int parse(final CharBufferToIntFunction applyChars) {
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
  long parse(final CharBufferToLongFunction applyChars) {
    final int len = parse();
    return applyChars.applyAsLong(charBuf, 0, len);
  }



  @Override
  <C> long parse(final C context, final ContextCharBufferToLongFunction<C> applyChars) {
    final int len = parse();
    return applyChars.applyAsLong(context, charBuf, 0, len);
  }

  @Override
  boolean parse(final CharBufferPredicate testChars) {
    final int len = parse();
    return testChars.apply(charBuf, 0, len);
  }

  @Override
  <C> boolean parse(final C context, final ContextCharBufferPredicate<C> testChars) {
    final int len = parse();
    return testChars.apply(context, charBuf, 0, len);
  }

  @Override
  void parse(final CharBufferConsumer testChars) {
    final int len = parse();
    testChars.accept(charBuf, 0, len);
  }

  @Override
  <C> void parse(final C context, final ContextCharBufferConsumer<C> testChars) {
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
  <C> boolean breakOut(final C context,
                       final ContextFieldBufferPredicate<C> fieldBufferFunction,
                       final int offset,
                       final int len) {
    return !fieldBufferFunction.test(context, charBuf, 0, len, this);
  }

  @Override
  <R> R apply(final FieldBufferFunction<R> fieldBufferFunction, final int offset, final int len) {
    return fieldBufferFunction.apply(charBuf, 0, len, this);
  }

  @Override
  <C, R> R apply(final C context,
                 final ContextFieldBufferFunction<C, R> fieldBufferFunction,
                 final int offset,
                 final int len) {
    return fieldBufferFunction.apply(context, charBuf, 0, len, this);
  }

  @Override
  BigDecimal parseBigDecimal(final CharBufferFunction<BigDecimal> parseChars) {
    return parseChars.apply(charBuf, 0, parseNumber());
  }

  private int parseMultiByteString(int j) {
    boolean isExpectingLowSurrogate = false;
    final int lanes = VectorSupport.BYTE_LANES;
    for (int bc; head < tail; ) {
      // Bulk-copy runs of clean ASCII between escapes / multi-byte characters.
      while (head + lanes <= tail) {
        final var chunk = ByteVector.fromArray(VectorSupport.BYTE_SPECIES, buf, head);
        final var stopMask = chunk.eq(QUOTE)
            .or(chunk.eq(BACKSLASH))
            .or(chunk.compare(VectorOperators.LT, (byte) 0));
        final long stop = stopMask.anyTrue() ? stopMask.toLong() : 0;
        if (stop == 0) {
          ensureCharBufCapacity(j + lanes);
          widenToCharBuf(chunk, j);
          j += lanes;
          head += lanes;
        } else {
          final int n = Long.numberOfTrailingZeros(stop);
          ensureCharBufCapacity(j + n);
          for (int i = 0; i < n; ++i) {
            charBuf[j + i] = (char) buf[head + i];
          }
          j += n;
          head += n;
          break;
        }
      }
      if (head == tail) {
        break;
      }
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
  String parsedNumberAsString(final int len) {
    return new String(charBuf, 0, len);
  }

  @Override
  <R> R parseNumber(final CharBufferFunction<R> applyChars, final int len) {
    return applyChars.apply(charBuf, 0, len);
  }

  @Override
  <C, R> R parseNumber(final C context,
                       final ContextCharBufferFunction<C, R> applyChars,
                       final int len) {
    return applyChars.apply(context, charBuf, 0, len);
  }

  @Override
  int parseNumber(final CharBufferToIntFunction applyChars, final int len) {
    return applyChars.applyAsInt(charBuf, 0, len);
  }

  @Override
  <C> int parseNumber(final C context, final ContextCharBufferToIntFunction<C> applyChars, final int len) {
    return applyChars.applyAsInt(context, charBuf, 0, len);
  }

  @Override
  long parseNumber(final CharBufferToLongFunction applyChars, final int len) {
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
  void skipContainer(final char open, final char close, int level) {
    final int lanes = VectorSupport.BYTE_LANES;
    final byte openByte = (byte) open;
    final byte closeByte = (byte) close;
    int h = head;
    outer:
    while (h + lanes <= tail) {
      final var chunk = ByteVector.fromArray(VectorSupport.BYTE_SPECIES, buf, h);
      // One combined extraction: the byte at each marked position identifies
      // which of the three markers it is, so per-marker masks are unnecessary.
      long bits = chunk.eq(openByte).or(chunk.eq(closeByte)).or(chunk.eq(QUOTE)).toLong();
      while (bits != 0) {
        final int n = Long.numberOfTrailingZeros(bits);
        final byte c = buf[h + n];
        if (c == QUOTE) {
          head = h + n + 1;
          skipPastEndQuote();
          h = head;
          continue outer;
        } else if (c == openByte) {
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


  /// SWAR conversion of eight ASCII digits at once, or -1 if any of the eight
  /// bytes is not a digit. simdjson's parse_eight_digits trick; the digit test
  /// is from Lemire, and the extraction folds pairs with three multiplies.
  private static long convertEightDigits(final long word) {
    if (((word & 0xF0F0F0F0F0F0F0F0L) | (((word + 0x0606060606060606L) & 0xF0F0F0F0F0F0F0F0L) >>> 4)) != 0x3030303030303030L) {
      return -1;
    }
    long value = word & 0x0F0F0F0F0F0F0F0FL;
    value = (value * 2561) >>> 8;
    value = (value & 0x00FF00FF00FF00FFL) * 6553601 >>> 16;
    return (value & 0x0000FFFF0000FFFFL) * 42949672960001L >>> 32;
  }

  @Override
  long readLong(final char c) {
    final int ind = INT_DIGITS[c];
    if (ind == 0) {
      assertNotLeadingZero();
      return 0;
    } else if (ind == INVALID_CHAR_FOR_NUMBER) {
      throw reportError("readLong", "expected 0~9");
    }
    long value = -ind; // accumulate negated to avoid a redundant Long.MIN_VALUE check per digit
    while (head + Long.BYTES <= tail && value >= -92233720367L) { // appending 8 digits cannot overflow
      final long digits = convertEightDigits((long) TO_LONG.get(buf, head));
      if (digits < 0) {
        break;
      }
      value = value * 100_000_000 - digits;
      head += Long.BYTES;
    }
    return continueLong(value);
  }

  /// Scalar continuation of [#readLong(char)] with the same overflow contract
  /// as BaseJsonIterator.readLongSlowPath.
  private long continueLong(long value) {
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

  @Override
  int parseNumber() {
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
        case ' ' -> {
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
