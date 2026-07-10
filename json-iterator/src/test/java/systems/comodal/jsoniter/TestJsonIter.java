package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Base64;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

final class TestJsonIter {

  @Test
  void test_readme() {
    final var ji = JsonIter.parse("{\"hello\": \"world\"}");
    assertEquals("hello", ji.nextField());
    assertEquals("world", ji.readString());
    assertNull(ji.nextField());
  }

  @Test
  void test_field_loop() {
    final var ji = JsonIter.parse("""
        {"symbol": "SOL", "price": 172.35, "active": true, "tags": ["l1", "pos"], "meta": null}""");
    String symbol = null;
    BigDecimal price = null;
    boolean active = false;
    int tags = 0;
    for (String field; (field = ji.nextField()) != null; ) {
      switch (field) {
        case "symbol" -> symbol = ji.readString();
        case "price" -> price = ji.readBigDecimal();
        case "active" -> active = ji.readBoolean();
        case "tags" -> {
          while (ji.readArray()) {
            ji.readString();
            ++tags;
          }
        }
        default -> ji.skip();
      }
    }
    assertEquals("SOL", symbol);
    assertEquals(new BigDecimal("172.35"), price);
    assertTrue(active);
    assertEquals(2, tags);
  }

  @Test
  void test_iterator_api() {
    final var ji = JsonIter.parse("[0,1,2,3]");
    int total = 0;
    while (ji.readArray()) {
      total += ji.readInt();
    }
    assertEquals(6, total);
  }

  @Test
  void test_nested_iteration() {
    final var ji = JsonIter.parse("{\"numbers\": [\"1\", \"2\", [\"3\", \"4\"]]}");
    assertEquals("numbers", ji.nextField());
    assertTrue(ji.readArray());
    assertEquals("1", ji.readString());
    assertTrue(ji.readArray());
    assertEquals("2", ji.readString());
    assertTrue(ji.readArray());
    assertEquals(ValueType.ARRAY, ji.whatIsNext());
    assertTrue(ji.readArray()); // start inner array
    assertEquals(ValueType.STRING, ji.whatIsNext());
    assertEquals("3", ji.readString());
    assertTrue(ji.readArray());
    assertEquals("4", ji.readString());
    assertFalse(ji.readArray()); // end inner array
    assertFalse(ji.readArray()); // end outer array
    assertNull(ji.nextField()); // end object
  }

  @Test
  void test_empty_containers() {
    assertNull(JsonIter.parse("{}").nextField());
    assertFalse(JsonIter.parse("[]").readArray());

    var ji = JsonIter.parse("[]");
    assertEquals(ji, ji.openArray());
    assertEquals(ji, ji.closeArray());

    ji = JsonIter.parse("{\"a\":{},\"b\":[]}");
    assertEquals("a", ji.nextField());
    assertNull(ji.nextField());
    assertEquals("b", ji.nextField());
    assertFalse(ji.readArray());
    assertNull(ji.nextField());
  }

  @Test
  void test_whitespace() {
    final var ji = JsonIter.parse("  { \"a\"\n :\t [ 1 ,\r\n 2 ] , \"b\" : \"c\" }  ");
    assertEquals("a", ji.nextField());
    assertTrue(ji.readArray());
    assertEquals(1, ji.readInt());
    assertTrue(ji.readArray());
    assertEquals(2, ji.readInt());
    assertFalse(ji.readArray());
    assertEquals("b", ji.nextField());
    assertEquals("c", ji.readString());
    assertNull(ji.nextField());
  }

  @Test
  void test_skip() {
    var ji = JsonIter.parse("[1,2]");
    assertTrue(ji.readArray());
    ji.skip();
    assertTrue(ji.readArray());
    assertEquals(2, ji.readInt());
    assertFalse(ji.readArray());

    ji = JsonIter.parse("[\"hello \\\" } ] world\",2]");
    assertTrue(ji.readArray());
    ji.skip();
    assertTrue(ji.readArray());
    assertEquals(2, ji.readInt());
    assertFalse(ji.readArray());

    ji = JsonIter.parse("[{\"hello\": {\"world\": \"a\"}},2]");
    assertTrue(ji.readArray());
    ji.skip();
    assertTrue(ji.readArray());
    assertEquals(2, ji.readInt());
    assertFalse(ji.readArray());

    ji = JsonIter.parse("[ [1, {\"a\": [\"b\"] },  3] ,2]");
    assertTrue(ji.readArray());
    ji.skip();
    assertTrue(ji.readArray());
    assertEquals(2, ji.readInt());
    assertFalse(ji.readArray());

    ji = JsonIter.parse("[true,false,null,2]");
    assertTrue(ji.readArray());
    ji.skip();
    assertTrue(ji.readArray());
    ji.skip();
    assertTrue(ji.readArray());
    ji.skip();
    assertTrue(ji.readArray());
    assertEquals(2, ji.readInt());
    assertFalse(ji.readArray());
  }

  @Test
  void test_skip_until() {
    var ji = JsonIter.parse("{ \"field1\" : \"hello\" , \"field2\": \"world\" }");
    assertEquals("world", ji.skipUntil("field2").readString());

    ji = JsonIter.parse("{ \"field1\" : \"hello\" , \"field2\": {\"nested1\" : \"blah\", \"nested2\": \"world\"} }");
    assertEquals("world", ji.skipUntil("field2").skipUntil("nested2").readString());

    ji = JsonIter.parse("{ \"a\" : 1 }");
    assertNull(ji.skipUntil("missing"));

    ji = JsonIter.parse("{}");
    assertNull(ji.skipUntil("missing"));

    // continue mid-object
    ji = JsonIter.parse("{ \"a\" : 1, \"b\" : 2, \"c\" : 3 }");
    assertEquals(1, ji.skipUntil("a").readInt());
    assertEquals(3, ji.skipUntil("c").readInt());
  }

  @Test
  void test_skip_rest_of_object_and_array() {
    var ji = JsonIter.parse("{\"a\":1,\"b\":{\"x\":[1,2]},\"c\":3}[4]");
    assertEquals("a", ji.nextField());
    assertEquals(1, ji.readInt());
    ji.skipRestOfObject();
    assertTrue(ji.readArray());
    assertEquals(4, ji.readInt());

    ji = JsonIter.parse("[1,[2,3],{\"a\":4},5]true");
    assertTrue(ji.readArray());
    assertEquals(1, ji.readInt());
    ji.skipRestOfArray();
    assertTrue(ji.readBoolean());
  }

  @Test
  void test_strings() {
    assertEquals("hello", JsonIter.parse("\"hello\"").readString());
    assertEquals("", JsonIter.parse("\"\"").readString());
    assertNull(JsonIter.parse("null").readString());
    assertEquals("he\tllo", JsonIter.parse("\"he\\tllo\"").readString());
    assertEquals("\b\f\n\r\t\"/\\", JsonIter.parse("\"\\b\\f\\n\\r\\t\\\"\\/\\\\\"").readString());
    assertEquals("中文", JsonIter.parse("\"中文\"").readString());
    assertEquals("中", JsonIter.parse("\"\\u4e2d\"").readString());
    assertEquals("👊", JsonIter.parse("\"\\ud83d\\udc4a\"").readString());
    assertEquals("👊", JsonIter.parse("\"👊\"").readString());
    assertEquals("prefix 中文 👊 suffix", JsonIter.parse("\"prefix 中文 👊 suffix\"").readString());

    assertEquals("even" + "\\".repeat(21), JsonIter.parse("\"even" + "\\".repeat(42) + '"').readString());
    assertEquals("odd" + "\\".repeat(5) + '"', JsonIter.parse("\"odd" + "\\".repeat(11) + "\"\"").readString());
    assertEquals("even\\", JsonIter.parse("\"even\\\\\"").readString());
    assertEquals("odd\\\"", JsonIter.parse("\"odd\\\\\\\"\"").readString());
    assertEquals("odd\"", JsonIter.parse("\"odd\\\"\"").readString());

    final var ji = JsonIter.parse("\"hello\"\"world\"");
    assertEquals("hello", ji.readString());
    assertEquals("world", ji.readString());
  }

  @Test
  void test_long_strings_across_blocks() {
    // Exercise strings spanning multiple 64-byte indexing blocks and vector widths.
    for (int len = 1; len <= 300; len += 7) {
      final var value = "x".repeat(len);
      final var json = "{\"pad\":\"" + value + "\",\"n\":" + len + '}';
      final var ji = JsonIter.parse(json);
      assertEquals(value, ji.skipUntil("pad").readString(), "len=" + len);
      assertEquals(len, ji.skipUntil("n").readInt(), "len=" + len);
    }
    // Escape at every possible position relative to the vector width.
    for (int prefix = 0; prefix <= 70; ++prefix) {
      final var expected = " ".repeat(prefix) + "\"tail";
      final var json = '"' + " ".repeat(prefix) + "\\\"tail\"";
      assertEquals(expected, JsonIter.parse(json).readString(), "prefix=" + prefix);
    }
  }

  @Test
  void test_base64() {
    final var encoder = Base64.getEncoder();
    final long seed = new Random().nextLong();
    final var random = new Random(seed);
    for (final int len : new int[]{0, 1, 2, 3, 10, 63, 64, 65, 1_000, 4_096}) {
      final var data = new byte[len];
      random.nextBytes(data);
      final var ji = JsonIter.parse("{\"data\":\"" + encoder.encodeToString(data) + "\"}");
      assertArrayEquals(data, ji.skipUntil("data").decodeBase64String(), "seed=" + seed + " len=" + len);
    }
    assertNull(JsonIter.parse("null").decodeBase64String());
  }

  @Test
  void test_integers() {
    assertEquals(0, JsonIter.parse("0").readInt());
    assertEquals(4321, JsonIter.parse("4321").readInt());
    assertEquals(-4321, JsonIter.parse("-4321").readInt());
    assertEquals(Integer.MAX_VALUE, JsonIter.parse(Integer.toString(Integer.MAX_VALUE)).readInt());
    assertEquals(Integer.MIN_VALUE, JsonIter.parse(Integer.toString(Integer.MIN_VALUE)).readInt());
    assertEquals(0, JsonIter.parse("\"0\"").readInt());
    assertEquals(-4321, JsonIter.parse("\"-4321\"").readInt());

    assertEquals(Long.MAX_VALUE, JsonIter.parse(Long.toString(Long.MAX_VALUE)).readLong());
    assertEquals(Long.MIN_VALUE, JsonIter.parse(Long.toString(Long.MIN_VALUE)).readLong());
    assertEquals(Long.MAX_VALUE, JsonIter.parse('"' + Long.toString(Long.MAX_VALUE) + '"').readLong());
    assertEquals(Long.MIN_VALUE, JsonIter.parse('"' + Long.toString(Long.MIN_VALUE) + '"').readLong());

    assertEquals((short) 321, JsonIter.parse("321").readShort());
    assertThrows(JsonException.class, () -> JsonIter.parse("40000").readShort());

    assertThrows(JsonException.class, () -> JsonIter.parse("2147483648").readInt());
    assertThrows(JsonException.class, () -> JsonIter.parse("-2147483649").readInt());
    assertThrows(JsonException.class, () -> JsonIter.parse("9223372036854775808").readLong());
    assertThrows(JsonException.class, () -> JsonIter.parse("-9223372036854775809").readLong());
    assertThrows(JsonException.class, () -> JsonIter.parse("92233720368547758070").readLong());

    assertThrows(JsonException.class, () -> JsonIter.parse("01").readInt());
    assertThrows(JsonException.class, () -> JsonIter.parse("\"01\"").readLong());
    assertEquals(0, JsonIter.parse("-0").readInt());
  }

  @Test
  void test_floats() {
    assertEquals(12.3f, JsonIter.parse("12.3").readFloat());
    assertEquals(-12.3d, JsonIter.parse("-12.3").readDouble());
    assertEquals(0.00123d, JsonIter.parse("123e-5").readDouble());
    assertEquals(Double.parseDouble("8.37377E9"), JsonIter.parse("8.37377E9").readDouble());
    assertEquals("1.7976931348623157e+308", JsonIter.parse("1.7976931348623157e+308").readNumberAsString());
    assertEquals(Double.POSITIVE_INFINITY, JsonIter.parse("\"Infinity\"").readDouble());
    assertEquals(Double.NaN, JsonIter.parse("\"NaN\"").readDouble());

    assertEquals(new BigDecimal("100.100"), JsonIter.parse("100.100").readBigDecimal());
    assertEquals(new BigDecimal("100.100"), JsonIter.parse("\"100.100\"").readBigDecimal());
    assertNull(JsonIter.parse("\"\"").readBigDecimal());
    assertNull(JsonIter.parse("null").readBigDecimal());

    assertEquals(new BigInteger("123456789012345678901234567890"), JsonIter.parse("123456789012345678901234567890").readBigInteger());
    assertNull(JsonIter.parse("null").readBigInteger());
  }

  @Test
  void test_booleans_and_null() {
    assertTrue(JsonIter.parse("true").readBoolean());
    assertFalse(JsonIter.parse("false").readBoolean());
    assertTrue(JsonIter.parse("null").readNull());
    assertFalse(JsonIter.parse("true").readNull());
    assertThrows(JsonException.class, () -> JsonIter.parse("null").readBoolean());

    final var ji = JsonIter.parse("[true,false,null,true]");
    assertTrue(ji.openArray().readBoolean());
    assertFalse(ji.continueArray().readBoolean());
    assertTrue(ji.continueArray().readNull());
    assertTrue(ji.continueArray().readBoolean());
    assertNotNull(ji.closeArray());
  }

  @Test
  void test_null_values() {
    var ji = JsonIter.parse("{\"field\":null}");
    assertEquals("field", ji.nextField());
    assertNull(ji.readString());
    assertNull(ji.nextField());

    ji = JsonIter.parse("null");
    assertNull(ji.nextField());
    ji = JsonIter.parse("null");
    assertFalse(ji.readArray());
  }

  @Test
  void test_what_is_next() {
    assertEquals(ValueType.OBJECT, JsonIter.parse("{}").whatIsNext());
    assertEquals(ValueType.STRING, JsonIter.parse("\"string\"").whatIsNext());
    assertEquals(ValueType.ARRAY, JsonIter.parse("[\"array\"]").whatIsNext());
    assertEquals(ValueType.BOOLEAN, JsonIter.parse("true").whatIsNext());
    assertEquals(ValueType.BOOLEAN, JsonIter.parse("false").whatIsNext());
    assertEquals(ValueType.NULL, JsonIter.parse("null").whatIsNext());
    assertEquals(ValueType.NUMBER, JsonIter.parse("-1").whatIsNext());
    assertEquals(ValueType.NUMBER, JsonIter.parse("5").whatIsNext());
  }

  @Test
  void test_instant() {
    for (final var dateTime : new String[]{
        "2018-03-15T01:23:44.349000Z",
        "2018-04-07T18:27:12.646Z",
        "2018-03-31T19:48:23.0752385Z"}) {
      assertEquals(Instant.parse(dateTime), JsonIter.parse('"' + dateTime + '"').readDateTime());
    }
    assertNull(JsonIter.parse("null").readDateTime());
  }

  @Test
  void test_mark_reset() {
    final var ji = JsonIter.parse("{\"a\":1,\"b\":2}");
    final int mark = ji.mark();
    assertEquals("a", ji.nextField());
    assertEquals(1, ji.readInt());
    ji.reset(mark);
    assertEquals(1, ji.skipUntil("a").readInt());
    assertEquals(2, ji.skipUntil("b").readInt());
  }

  @Test
  void test_iterator_reuse() {
    final var ji = JsonIter.parse("{\"a\":1}");
    assertEquals(1, ji.skipUntil("a").readInt());
    ji.reset("{\"bb\":\"22\"}");
    assertEquals("22", ji.skipUntil("bb").readString());
    ji.reset("[3]".getBytes());
    assertTrue(ji.readArray());
    assertEquals(3, ji.readInt());
  }

  @Test
  void test_invalid_documents() {
    assertThrows(JsonException.class, () -> JsonIter.parse("\"unclosed"));
    assertThrows(JsonException.class, () -> JsonIter.parse("{\"a\":\"unclosed}"));
    assertThrows(JsonException.class, () -> JsonIter.parse("\"control\tchar\""));
    assertThrows(JsonException.class, () -> JsonIter.parse("\"bad\\escape\"").readString());
    assertThrows(JsonException.class, () -> JsonIter.parse("\"\\ud83dnolow\"").readString());
    assertThrows(JsonException.class, () -> JsonIter.parse("{\"a\":1").skipUntil("b"));
    assertThrows(JsonException.class, () -> JsonIter.parse("[1,2").skipRestOfArray());
    assertThrows(JsonException.class, () -> JsonIter.parse("x").readInt());
  }

  @Test
  void test_utf8_validation() {
    assertEquals("中文👊", JsonIter.parseValidating("{\"s\":\"中文👊\"}".getBytes()).skipUntil("s").readString());
    assertEquals(1, JsonIter.parseValidating("{\"a\":1}".getBytes()).skipUntil("a").readInt());

    // lone continuation byte
    assertThrows(JsonException.class, () -> JsonIter.parseValidating(utf8Doc((byte) 0x80)));
    // overlong 2-byte encoding of '/'
    assertThrows(JsonException.class, () -> JsonIter.parseValidating(utf8Doc((byte) 0xC0, (byte) 0xAF)));
    // UTF-16 surrogate U+D800 encoded in UTF-8
    assertThrows(JsonException.class, () -> JsonIter.parseValidating(utf8Doc((byte) 0xED, (byte) 0xA0, (byte) 0x80)));
    // code point above U+10FFFF
    assertThrows(JsonException.class, () -> JsonIter.parseValidating(utf8Doc((byte) 0xF5, (byte) 0x80, (byte) 0x80, (byte) 0x80)));
    // truncated 3-byte sequence inside the document
    assertThrows(JsonException.class, () -> JsonIter.parseValidating(utf8Doc((byte) 0xE4, (byte) 0xB8)));
    // truncated sequence at the very end of the input
    assertThrows(JsonException.class, () -> JsonIter.parseValidating(new byte[]{'"', 'x', '"', ' ', (byte) 0xE4, (byte) 0xB8}));
    // long valid documents remain valid across chunk boundaries
    final var longDoc = "{\"s\":\"" + "中文👊 mixed with ascii ".repeat(50) + "\"}";
    assertNotNull(JsonIter.parseValidating(longDoc.getBytes()).skipUntil("s").readString());

    // without validation, malformed UTF-8 passes through un-checked
    assertNotNull(JsonIter.parse(utf8Doc((byte) 0xC0, (byte) 0xAF)).readString());
  }

  private static byte[] utf8Doc(final byte... content) {
    final byte[] doc = new byte[content.length + 2];
    doc[0] = '"';
    System.arraycopy(content, 0, doc, 1, content.length);
    doc[doc.length - 1] = '"';
    return doc;
  }

  @Test
  void test_escaped_field_names() {
    final var ji = JsonIter.parse("{\"a\\tb\":1,\"c\":2}");
    assertEquals("a\tb", ji.nextField());
    assertEquals(1, ji.readInt());

    assertEquals(2, JsonIter.parse("{\"a\\tb\":1,\"c\":2}").skipUntil("c").readInt());
    assertEquals(1, JsonIter.parse("{\"a\\tb\":1,\"c\":2}").skipUntil("a\tb").readInt());
    assertEquals(1, JsonIter.parse("{\"中文\":1}").skipUntil("中文").readInt());
    assertNull(JsonIter.parse("{\"ab\":1}").skipUntil("abc"));
    assertNull(JsonIter.parse("{\"abc\":1}").skipUntil("ab"));
  }

  @Test
  void test_cross_check_with_json_iterator() {
    final var json = """
        {
          "id": 9223372036854775807,
          "name": "cross \\"check\\" 中文 👊",
          "values": [1.5, -2, 3e2, 0.001],
          "nested": {"deep": {"deeper": [{"x": 1}, {"y": null}]}},
          "flag": false,
          "big": "123456789.000000001"
        }""";

    final var iter = JsonIterator.parse(json);
    final var vec = JsonIter.parse(json);

    assertEquals(iter.readObjField(), vec.nextField());
    assertEquals(iter.readLong(), vec.readLong());
    assertEquals(iter.readObjField(), vec.nextField());
    assertEquals(iter.readString(), vec.readString());
    assertEquals(iter.readObjField(), vec.nextField());
    for (int i = 0; i < 4; ++i) {
      assertEquals(iter.readArray(), vec.readArray());
      assertEquals(iter.readDouble(), vec.readDouble());
    }
    assertEquals(iter.readArray(), vec.readArray());
    assertEquals(iter.readObjField(), vec.nextField());
    iter.skip();
    vec.skip();
    assertEquals(iter.readObjField(), vec.nextField());
    assertEquals(iter.readBoolean(), vec.readBoolean());
    assertEquals(iter.readObjField(), vec.nextField());
    assertEquals(iter.readBigDecimal(), vec.readBigDecimal());
    assertEquals(iter.readObjField(), vec.nextField());
  }

  @Test
  void test_large_document() {
    final var sb = new StringBuilder(1 << 16).append('[');
    for (int i = 0; i < 2_000; ++i) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append("{\"i\":").append(i).append(",\"s\":\"value ").append(i).append("\"}");
    }
    final var ji = JsonIter.parse(sb.append(']').toString());
    int count = 0;
    long sum = 0;
    while (ji.readArray()) {
      assertEquals("i", ji.nextField());
      sum += ji.readLong();
      assertEquals("s", ji.nextField());
      assertEquals("value " + count, ji.readString());
      assertNull(ji.nextField());
      ++count;
    }
    assertEquals(2_000, count);
    assertEquals(1_999L * 2_000 / 2, sum);
  }
}
