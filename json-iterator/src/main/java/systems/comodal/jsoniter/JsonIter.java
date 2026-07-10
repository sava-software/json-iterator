package systems.comodal.jsoniter;

import jdk.incubator.vector.ByteVector;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

import static systems.comodal.jsoniter.ValueType.*;

/// A SIMD accelerated JSON iterator built on simdjson's two-stage design,
/// using the incubating Vector API ([jdk.incubator.vector.ByteVector]).
///
/// Stage 1 ([StructuralIndex]) scans the entire document with vector
/// instructions and records the position of every structural token: braces,
/// brackets, colons, commas, opening quotes, and the first byte of every
/// number and literal. Escaped quotes and structural characters inside
/// strings are resolved during that pass.
///
/// Stage 2 — this class — navigates by hopping between those tokens:
/// - [#skip()] over any value, however large or deeply nested, touches only
///   structural tokens and never rescans string bytes.
/// - Values are decoded lazily and only when asked for.
///
/// In contrast to [JsonIterator], the full document must be in memory; there
/// is no streaming mode. The iterator keeps a private padded copy of the
/// document, and both it and the structural index are reused across
/// [#reset(byte[])] calls, so a single instance can parse many documents
/// without further allocation.
///
/// Example:
/// ```java
/// var ji = JsonIter.parse("""
///     {"symbol": "SOL", "price": 172.35, "tags": ["l1", "pos"]}""");
/// for (String field; (field = ji.nextField()) != null; ) {
///   switch (field) {
///     case "symbol" -> symbol = ji.readString();
///     case "price" -> price = ji.readBigDecimal();
///     default -> ji.skip();
///   }
/// }
/// ```
///
/// Instances are not thread safe.
public final class JsonIter {

  private static final int PADDING = StructuralIndex.BLOCK;

  private final StructuralIndex index;
  private final boolean validateUtf8;
  private byte[] buf;
  private int len;
  private int[] tokens;
  private int numTokens;
  private int pos;
  private byte[] stringBuffer;

  private JsonIter(final boolean validateUtf8) {
    this.index = new StructuralIndex();
    this.validateUtf8 = validateUtf8;
    this.buf = new byte[256];
    this.stringBuffer = new byte[64];
  }

  public static JsonIter parse(final byte[] json) {
    return new JsonIter(false).reset(json, 0, json.length);
  }

  public static JsonIter parse(final byte[] json, final int offset, final int len) {
    return new JsonIter(false).reset(json, offset, len);
  }

  public static JsonIter parse(final String json) {
    return parse(json.getBytes(StandardCharsets.UTF_8));
  }

  /// Like [#parse(byte[])], but the document is first checked to be valid
  /// UTF-8 with a vectorized validator ([Utf8Validator]); a [JsonException] is
  /// thrown otherwise. The check also applies to documents passed to the
  /// `reset` methods of the returned iterator.
  public static JsonIter parseValidating(final byte[] json) {
    return new JsonIter(true).reset(json, 0, json.length);
  }

  public static JsonIter parseValidating(final byte[] json, final int offset, final int len) {
    return new JsonIter(true).reset(json, offset, len);
  }

  /// Re-indexes this iterator over a new document, reusing internal buffers.
  public JsonIter reset(final byte[] json) {
    return reset(json, 0, json.length);
  }

  public JsonIter reset(final byte[] json, final int offset, final int len) {
    if (buf.length < len + PADDING) {
      buf = new byte[len + PADDING + PADDING];
    }
    System.arraycopy(json, offset, buf, 0, len);
    Arrays.fill(buf, len, Math.min(buf.length, len + PADDING + PADDING), (byte) ' ');
    this.len = len;
    if (validateUtf8) {
      Utf8Validator.validate(buf, len);
    }
    index.index(buf, len);
    this.tokens = index.indexes();
    this.numTokens = index.count();
    this.pos = 0;
    return this;
  }

  public JsonIter reset(final String json) {
    return reset(json.getBytes(StandardCharsets.UTF_8));
  }

  /// The current token position. Unlike [JsonIterator#mark()], this is a
  /// token ordinal, not a byte offset, and rewinding is always supported.
  public int mark() {
    return pos;
  }

  public JsonIter reset(final int mark) {
    this.pos = mark;
    return this;
  }

  public ValueType whatIsNext() {
    return VALUE_TYPES[buf[tokens[pos]] & 0xff];
  }

  public String currentBuffer() {
    final int at = tokens[Math.min(pos, numTokens)];
    final int from = Math.max(0, at - 16);
    return "token: " + pos + ", at: " + at + ", peek: " + new String(buf, from, Math.min(len, at + 16) - from, StandardCharsets.UTF_8);
  }

  private JsonException reportError(final String op, final String msg) {
    throw new JsonException(op + ": " + msg + ", " + currentBuffer());
  }

  private char nextToken() {
    if (pos >= numTokens) {
      throw reportError("nextToken", "unexpected end of data");
    }
    return (char) (buf[tokens[pos++]] & 0xff);
  }

  private char peekToken() {
    if (pos >= numTokens) {
      throw reportError("peekToken", "unexpected end of data");
    }
    return (char) (buf[tokens[pos]] & 0xff);
  }

  // Arrays

  /// Same contract as [JsonIterator#readArray()]: returns true if positioned
  /// on the next element, false once the array (or a null literal) ends.
  public boolean readArray() {
    final char c = nextToken();
    if (c == '[') {
      if (peekToken() == ']') {
        ++pos;
        return false;
      }
      return true;
    } else if (c == ',') {
      return true;
    } else if (c == ']') {
      return false;
    } else if (c == 'n') {
      return false;
    } else {
      throw reportError("readArray", "expected [ or , or n or ], but found: " + c);
    }
  }

  public JsonIter openArray() {
    final char c = nextToken();
    if (c == '[') {
      return this;
    }
    throw reportError("openArray", "expected '[' but found: " + c);
  }

  public JsonIter continueArray() {
    final char c = nextToken();
    if (c == ',') {
      return this;
    }
    throw reportError("continueArray", "expected ',' but found: " + c);
  }

  public JsonIter closeArray() {
    final char c = nextToken();
    if (c == ']') {
      return this;
    }
    throw reportError("closeArray", "expected ']' but found: " + c);
  }

  // Objects

  /// Advances to the next field of the current object and returns its name,
  /// or null once the object ends. The cursor is left on the field's value.
  public String nextField() {
    char c = nextToken();
    if (c == ',') {
      c = nextToken();
      if (c != '"') {
        throw reportError("nextField", "expected field string, but found: " + c);
      }
      final var field = parseString(tokens[pos - 1]);
      expectColon();
      return field;
    } else if (c == '{') {
      c = peekToken();
      if (c == '"') {
        final var field = parseString(tokens[pos++]);
        expectColon();
        return field;
      } else if (c == '}') {
        ++pos;
        return null;
      } else {
        throw reportError("nextField", "expected \" after {, but found: " + c);
      }
    } else if (c == '}') {
      return null;
    } else if (c == 'n') {
      return null;
    } else {
      throw reportError("nextField", "expected [,{}n], but found: " + c);
    }
  }

  private void expectColon() {
    final char c = nextToken();
    if (c != ':') {
      throw reportError("nextField", "expected :, but found: " + c);
    }
  }

  /// Advances until a field named `field` is found in the current object,
  /// leaving the cursor on its value, or returns null once the object ends.
  /// Field names are compared without allocating.
  public JsonIter skipUntil(final String field) {
    char c = nextToken();
    if (c == '{') {
      c = peekToken();
      if (c == '}') {
        ++pos;
        return null;
      }
      if (matchField(field)) {
        return this;
      }
      c = nextToken();
    }
    for (; ; c = nextToken()) {
      if (c == ',') {
        if (matchField(field)) {
          return this;
        }
      } else if (c == '}') {
        return null;
      } else {
        throw reportError("skipUntil", "expected [,{}], but found: " + c);
      }
    }
  }

  private boolean matchField(final String field) {
    final char c = nextToken();
    if (c != '"') {
      throw reportError("skipUntil", "expected field string, but found: " + c);
    }
    final boolean match = fieldEquals(field, tokens[pos - 1]);
    expectColon();
    if (match) {
      return true;
    }
    skip();
    return false;
  }

  private boolean fieldEquals(final String field, final int quotePos) {
    final int fieldLength = field.length();
    int i = quotePos + 1;
    for (int f = 0; f < fieldLength; ++f, ++i) {
      final byte b = buf[i];
      if (b == '\\' || b < 0) {
        // Escaped or multi-byte field names take the allocating slow path.
        return field.equals(parseString(quotePos));
      } else if (field.charAt(f) != (char) (b & 0xff)) {
        return false;
      }
    }
    return buf[i] == '"';
  }

  // Values

  /// Skips the next value. Scalars — including arbitrarily long strings — are
  /// a single token hop; containers cost one token per structural character.
  public JsonIter skip() {
    final char c = nextToken();
    switch (c) {
      case '{', '[' -> {
        for (int depth = 1; depth > 0; ) {
          final char t = nextToken();
          if (t == '{' || t == '[') {
            ++depth;
          } else if (t == '}' || t == ']') {
            --depth;
          }
        }
      }
      // scalars are a single token
      case '"', 't', 'f', 'n', '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> {
      }
      default -> throw reportError("skip", "Cannot skip: " + c);
    }
    return this;
  }

  public JsonIter skipRestOfObject() {
    for (int depth = 1; depth > 0; ) {
      final char t = nextToken();
      if (t == '{' || t == '[') {
        ++depth;
      } else if (t == '}' || t == ']') {
        --depth;
      }
    }
    return this;
  }

  public JsonIter skipRestOfArray() {
    return skipRestOfObject();
  }

  public JsonIter closeObj() {
    final char c = nextToken();
    if (c == '}') {
      return this;
    }
    throw reportError("closeObj", "expected '}' but found: " + c);
  }

  /// Advances past a null literal and returns true, otherwise stays in place.
  public boolean readNull() {
    if (peekToken() == 'n') {
      ++pos;
      return true;
    }
    return false;
  }

  public boolean readBoolean() {
    final char c = nextToken();
    if (c == 't') {
      return true;
    } else if (c == 'f') {
      return false;
    } else {
      throw reportError("readBoolean", "expected t or f, found: " + c);
    }
  }

  // Strings

  public String readString() {
    final char c = nextToken();
    if (c == '"') {
      return parseString(tokens[pos - 1]);
    } else if (c == 'n') {
      return null;
    } else {
      throw reportError("readString", "expected string or null, but " + c);
    }
  }

  public byte[] decodeBase64String() {
    final char c = nextToken();
    if (c == 'n') {
      return null;
    } else if (c != '"') {
      throw reportError("decodeBase64String", "expected string or null, but " + c);
    }
    final int from = tokens[pos - 1] + 1;
    return Base64.getDecoder().decode(Arrays.copyOfRange(buf, from, stringEnd(from)));
  }

  /// See [JsonIterator#readDateTime()].
  public Instant readDateTime() {
    final var str = readString();
    if (str == null) {
      return null;
    }
    return InstantParser.INSTANT_PARSER.apply(str.toCharArray(), 0, str.length());
  }

  private String parseString(final int quotePos) {
    final int lanes = VectorSupport.BYTE_LANES;
    final int from = quotePos + 1;
    for (int i = from; ; i += lanes) {
      final var chunk = ByteVector.fromArray(VectorSupport.BYTE_SPECIES, buf, i);
      final long backslash = chunk.eq((byte) '\\').toLong();
      final long quote = chunk.eq((byte) '"').toLong();
      if (quote != 0 && (backslash == 0 || Long.numberOfTrailingZeros(quote) < Long.numberOfTrailingZeros(backslash))) {
        // Multi-byte UTF-8 needs no special casing: its bytes are copied
        // verbatim and decoded by the String constructor.
        return new String(buf, from, i + Long.numberOfTrailingZeros(quote) - from, StandardCharsets.UTF_8);
      } else if (backslash != 0) {
        return parseEscapedString(from, i + Long.numberOfTrailingZeros(backslash));
      }
    }
  }

  private int stringEnd(final int from) {
    final int lanes = VectorSupport.BYTE_LANES;
    for (int i = from; ; i += lanes) {
      final long quote = ByteVector.fromArray(VectorSupport.BYTE_SPECIES, buf, i).eq((byte) '"').toLong();
      if (quote != 0) {
        return i + Long.numberOfTrailingZeros(quote);
      }
    }
  }

  private String parseEscapedString(final int from, final int firstEscape) {
    int dst = firstEscape - from;
    if (stringBuffer.length < dst + 8) {
      stringBuffer = Arrays.copyOf(stringBuffer, Math.max(stringBuffer.length << 1, dst + 8));
    }
    System.arraycopy(buf, from, stringBuffer, 0, dst);
    for (int i = firstEscape; ; ) {
      final byte b = buf[i];
      if (b == '"') {
        return new String(stringBuffer, 0, dst, StandardCharsets.UTF_8);
      }
      if (stringBuffer.length < dst + 8) {
        stringBuffer = Arrays.copyOf(stringBuffer, stringBuffer.length << 1);
      }
      if (b == '\\') {
        final byte escape = buf[i + 1];
        if (escape == 'u') {
          int codePoint = hex4(i + 2);
          i += 6;
          if (Character.isHighSurrogate((char) codePoint)) {
            if (buf[i] != '\\' || buf[i + 1] != 'u') {
              throw new JsonException("invalid surrogate");
            }
            final int low = hex4(i + 2);
            if (!Character.isLowSurrogate((char) low)) {
              throw new JsonException("invalid surrogate");
            }
            i += 6;
            codePoint = Character.toCodePoint((char) codePoint, (char) low);
          } else if (Character.isLowSurrogate((char) codePoint)) {
            throw new JsonException("invalid surrogate");
          }
          dst = encodeUtf8(codePoint, dst);
        } else {
          stringBuffer[dst++] = unescape(escape);
          i += 2;
        }
      } else {
        // Bulk-copy until the next escape or closing quote. Multi-byte UTF-8
        // passes through verbatim, so only quotes and backslashes stop the run.
        final var chunk = ByteVector.fromArray(VectorSupport.BYTE_SPECIES, buf, i);
        final long stop = chunk.eq((byte) '"').toLong() | chunk.eq((byte) '\\').toLong();
        final int n = stop == 0 ? VectorSupport.BYTE_LANES : Long.numberOfTrailingZeros(stop);
        if (stringBuffer.length < dst + VectorSupport.BYTE_LANES) {
          stringBuffer = Arrays.copyOf(stringBuffer, Math.max(stringBuffer.length << 1, dst + VectorSupport.BYTE_LANES));
        }
        chunk.intoArray(stringBuffer, dst);
        dst += n;
        i += n;
      }
    }
  }

  private int hex4(final int i) {
    return (JHex.decode(buf[i]) << 12)
        | (JHex.decode(buf[i + 1]) << 8)
        | (JHex.decode(buf[i + 2]) << 4)
        | JHex.decode(buf[i + 3]);
  }

  private static byte unescape(final byte escape) {
    return switch (escape) {
      case 'b' -> '\b';
      case 't' -> '\t';
      case 'n' -> '\n';
      case 'f' -> '\f';
      case 'r' -> '\r';
      case '"', '/', '\\' -> escape;
      default -> throw new JsonException("invalid escape character: " + escape);
    };
  }

  private int encodeUtf8(final int codePoint, int dst) {
    if (codePoint < 0x80) {
      stringBuffer[dst++] = (byte) codePoint;
    } else if (codePoint < 0x800) {
      stringBuffer[dst++] = (byte) (0b1100_0000 | (codePoint >> 6));
      stringBuffer[dst++] = (byte) (0b1000_0000 | (codePoint & 0x3F));
    } else if (codePoint < 0x10000) {
      stringBuffer[dst++] = (byte) (0b1110_0000 | (codePoint >> 12));
      stringBuffer[dst++] = (byte) (0b1000_0000 | ((codePoint >> 6) & 0x3F));
      stringBuffer[dst++] = (byte) (0b1000_0000 | (codePoint & 0x3F));
    } else {
      stringBuffer[dst++] = (byte) (0b1111_0000 | (codePoint >> 18));
      stringBuffer[dst++] = (byte) (0b1000_0000 | ((codePoint >> 12) & 0x3F));
      stringBuffer[dst++] = (byte) (0b1000_0000 | ((codePoint >> 6) & 0x3F));
      stringBuffer[dst++] = (byte) (0b1000_0000 | (codePoint & 0x3F));
    }
    return dst;
  }

  // Numbers

  private int numEnd;

  private int digit(final int i) {
    final int b = buf[i];
    return b < '0' || b > '9' ? -1 : b - '0';
  }

  private long parseLong(final String op, int i) {
    final boolean negative = buf[i] == '-';
    if (negative) {
      ++i;
    }
    int d = digit(i);
    if (d == -1) {
      throw reportError(op, "expected 0~9");
    }
    if (d == 0) {
      if (digit(i + 1) != -1) {
        throw reportError(op, "leading zero is invalid");
      }
      numEnd = i + 1;
      return 0;
    }
    long value = 0; // accumulate negated to avoid a redundant Long.MIN_VALUE check per digit
    for (; d != -1; d = digit(++i)) {
      if (value < -922337203685477580L) { // Long.MIN_VALUE / 10
        throw reportError(op, "value is too large for long");
      }
      value = (value << 3) + (value << 1) - d;
      if (value >= 0) {
        throw reportError(op, "value is too large for long");
      }
    }
    numEnd = i;
    if (negative) {
      return value;
    } else if (value == Long.MIN_VALUE) {
      throw reportError(op, "value is too large for long");
    } else {
      return -value;
    }
  }

  private long readLong(final String op) {
    final char c = nextToken();
    if (c == '"') {
      final long value = parseLong(op, tokens[pos - 1] + 1);
      if (buf[numEnd] != '"') {
        throw reportError(op, "Lenient parsing of number string did not close with a quote.");
      }
      return value;
    }
    return parseLong(op, tokens[pos - 1]);
  }

  public long readLong() {
    return readLong("readLong");
  }

  public int readInt() {
    final long value = readLong("readInt");
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
      throw reportError("readInt", "value is too large for int");
    }
    return (int) value;
  }

  public short readShort() {
    final long value = readLong("readShort");
    if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
      throw reportError("readShort", "short overflow: " + value);
    }
    return (short) value;
  }

  private int numberEnd(int i) {
    for (; ; ++i) {
      final int b = buf[i];
      if ((b < '0' || b > '9') && b != '-' && b != '+' && b != '.' && b != 'e' && b != 'E') {
        return i; // always terminates: the buffer is padded with spaces
      }
    }
  }

  public String readNumberAsString() {
    if (whatIsNext() != NUMBER) {
      throw reportError("readNumberAsString", "expected number, but found: " + whatIsNext());
    }
    final int start = tokens[pos++];
    return new String(buf, start, numberEnd(start) - start, StandardCharsets.ISO_8859_1);
  }

  public String readNumberOrNumberString() {
    final var valueType = whatIsNext();
    if (valueType == NUMBER) {
      return readNumberAsString();
    } else if (valueType == STRING) {
      return readString();
    } else if (valueType == NULL) {
      ++pos;
      return null;
    } else {
      throw reportError("readNumberOrNumberString", "Must be a number, string or null but found " + valueType);
    }
  }

  public double readDouble() {
    try {
      return Double.parseDouble(readNumberOrNumberString());
    } catch (final NumberFormatException e) {
      throw reportError("readDouble", e.toString());
    }
  }

  public float readFloat() {
    try {
      return Float.parseFloat(readNumberOrNumberString());
    } catch (final NumberFormatException e) {
      throw reportError("readFloat", e.toString());
    }
  }

  public BigDecimal readBigDecimal() {
    final var str = readNumberOrNumberString();
    return str == null || str.isEmpty() ? null : new BigDecimal(str);
  }

  public BigInteger readBigInteger() {
    final var str = readNumberOrNumberString();
    return str == null || str.isEmpty() ? null : new BigInteger(str);
  }
}
