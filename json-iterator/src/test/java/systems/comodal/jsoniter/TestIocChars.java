package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.FieldSource;
import systems.comodal.jsoniter.factories.JsonIteratorFactory;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Exercises the inversion-of-control char-buffer surface: every
/// `applyChars*`/`testChars`/`consumeChars` overload and the
/// `applyNumberChars*` family, across string values (escaped and unescaped),
/// null values, and non-string error tokens.
///
/// The unescaped assertions also pin the zero-copy contract: an unescaped
/// value must be handed over as an in-place span of a larger buffer, never as
/// an exact-length unescape copy (`buf.length > len`).
@ParameterizedClass
@FieldSource("systems.comodal.jsoniter.TestFactories#FACTORIES")
final class TestIocChars {

  private final JsonIteratorFactory factory;

  TestIocChars(final JsonIteratorFactory factory) {
    this.factory = factory;
  }

  private static int parseInt(final char[] buf, final int offset, final int len) {
    return Integer.parseInt(new String(buf, offset, len));
  }

  private static long parseLong(final char[] buf, final int offset, final int len) {
    return Long.parseLong(new String(buf, offset, len));
  }

  @Test
  void test_apply_chars() {
    final var ji = factory.create("[\"ab\",\"a\\tb\",null,7]");
    ji.openArray();
    assertEquals("ab", ji.applyChars((buf, offset, len) -> {
      assertTrue(buf.length > len, "unescaped value must be an in-place span");
      return new String(buf, offset, len);
    }));
    assertEquals("a\tb", ji.continueArray().applyChars((buf, offset, len) -> new String(buf, offset, len)));
    assertNull(ji.continueArray().applyChars((_, _, _) -> "x"));
    assertEquals(7, ji.continueArray().readInt());
    ji.closeArray();

    final var ex = assertThrows(JsonException.class, () -> factory.create("7").applyChars((_, _, _) -> "x"));
    assertEquals("applyChars", ex.op());
  }

  @Test
  void test_apply_chars_context() {
    final var ctx = new Object();
    final var ji = factory.create("[\"ab\",\"a\\tb\",null,7]");
    ji.openArray();
    assertEquals("ab", ji.applyChars(ctx, (context, buf, offset, len) -> {
      assertSame(ctx, context);
      assertTrue(buf.length > len, "unescaped value must be an in-place span");
      return new String(buf, offset, len);
    }));
    assertEquals("a\tb", ji.continueArray().applyChars(ctx, (context, buf, offset, len) -> {
      assertSame(ctx, context);
      return new String(buf, offset, len);
    }));
    assertNull(ji.continueArray().applyChars(ctx, (_, _, _, _) -> "x"));
    assertEquals(7, ji.continueArray().readInt());
    ji.closeArray();

    final var ex = assertThrows(JsonException.class, () -> factory.create("7").applyChars(ctx, (_, _, _, _) -> "x"));
    assertEquals("applyChars", ex.op());
  }

  @Test
  void test_apply_chars_as_int() {
    final var ji = factory.create("[\"123\",\"1\\u00323\",null,7]");
    ji.openArray();
    assertEquals(123, ji.applyCharsAsInt((buf, offset, len) -> {
      assertTrue(buf.length > len, "unescaped value must be an in-place span");
      return parseInt(buf, offset, len);
    }));
    assertEquals(123, ji.continueArray().applyCharsAsInt(TestIocChars::parseInt));
    assertEquals(-9, ji.continueArray().applyCharsAsInt((_, _, len) -> len == 0 ? -9 : -1));
    assertEquals(7, ji.continueArray().readInt());
    ji.closeArray();

    final var ex = assertThrows(JsonException.class, () -> factory.create("7").applyCharsAsInt((_, _, _) -> 1));
    assertEquals("applyCharsAsInt", ex.op());
  }

  @Test
  void test_apply_chars_as_int_context() {
    final var ctx = new Object();
    final var ji = factory.create("[\"123\",\"1\\u00323\",null,7]");
    ji.openArray();
    assertEquals(123, ji.applyCharsAsInt(ctx, (context, buf, offset, len) -> {
      assertSame(ctx, context);
      assertTrue(buf.length > len, "unescaped value must be an in-place span");
      return parseInt(buf, offset, len);
    }));
    assertEquals(123, ji.continueArray().applyCharsAsInt(ctx, (_, buf, offset, len) -> parseInt(buf, offset, len)));
    assertEquals(-9, ji.continueArray().applyCharsAsInt(ctx, (_, _, _, len) -> len == 0 ? -9 : -1));
    assertEquals(7, ji.continueArray().readInt());
    ji.closeArray();

    final var ex = assertThrows(JsonException.class, () -> factory.create("7").applyCharsAsInt(ctx, (_, _, _, _) -> 1));
    assertEquals("applyCharsAsInt", ex.op());
  }

  @Test
  void test_apply_chars_as_long() {
    final var ji = factory.create("[\"1234567890123\",\"1\\u00323\",null,7]");
    ji.openArray();
    assertEquals(1234567890123L, ji.applyCharsAsLong((buf, offset, len) -> {
      assertTrue(buf.length > len, "unescaped value must be an in-place span");
      return parseLong(buf, offset, len);
    }));
    assertEquals(123L, ji.continueArray().applyCharsAsLong(TestIocChars::parseLong));
    assertEquals(-9L, ji.continueArray().applyCharsAsLong((_, _, len) -> len == 0 ? -9L : -1L));
    assertEquals(7, ji.continueArray().readInt());
    ji.closeArray();

    final var ex = assertThrows(JsonException.class, () -> factory.create("7").applyCharsAsLong((_, _, _) -> 1L));
    assertEquals("applyCharsAsLong", ex.op());
  }

  @Test
  void test_apply_chars_as_long_context() {
    final var ctx = new Object();
    final var ji = factory.create("[\"1234567890123\",\"1\\u00323\",null,7]");
    ji.openArray();
    assertEquals(1234567890123L, ji.applyCharsAsLong(ctx, (context, buf, offset, len) -> {
      assertSame(ctx, context);
      assertTrue(buf.length > len, "unescaped value must be an in-place span");
      return parseLong(buf, offset, len);
    }));
    assertEquals(123L, ji.continueArray().applyCharsAsLong(ctx, (_, buf, offset, len) -> parseLong(buf, offset, len)));
    assertEquals(-9L, ji.continueArray().applyCharsAsLong(ctx, (_, _, _, len) -> len == 0 ? -9L : -1L));
    assertEquals(7, ji.continueArray().readInt());
    ji.closeArray();

    final var ex = assertThrows(JsonException.class, () -> factory.create("7").applyCharsAsLong(ctx, (_, _, _, _) -> 1L));
    assertEquals("applyCharsAsLong", ex.op());
  }

  @Test
  void test_test_chars() {
    final var ji = factory.create("[\"ab\",\"ab\",\"a\\tb\",\"a\\tb\",null,7]");
    ji.openArray();
    assertTrue(ji.testChars((buf, offset, len) -> {
      assertTrue(buf.length > len, "unescaped value must be an in-place span");
      return "ab".equals(new String(buf, offset, len));
    }));
    assertFalse(ji.continueArray().testChars((_, _, _) -> false));
    assertTrue(ji.continueArray().testChars((buf, offset, len) -> "a\tb".equals(new String(buf, offset, len))));
    assertFalse(ji.continueArray().testChars((buf, offset, len) -> {
      assertEquals("a\tb", new String(buf, offset, len));
      return false;
    }));
    assertFalse(ji.continueArray().testChars((_, _, _) -> true));
    assertEquals(7, ji.continueArray().readInt());
    ji.closeArray();

    final var ex = assertThrows(JsonException.class, () -> factory.create("7").testChars((_, _, _) -> true));
    assertEquals("testChars", ex.op());
  }

  @Test
  void test_test_chars_context() {
    final var ctx = new Object();
    final var ji = factory.create("[\"ab\",\"ab\",\"a\\tb\",\"a\\tb\",null,7]");
    ji.openArray();
    assertTrue(ji.testChars(ctx, (context, buf, offset, len) -> {
      assertSame(ctx, context);
      assertTrue(buf.length > len, "unescaped value must be an in-place span");
      return "ab".equals(new String(buf, offset, len));
    }));
    assertFalse(ji.continueArray().testChars(ctx, (_, _, _, _) -> false));
    assertTrue(ji.continueArray().testChars(ctx, (_, buf, offset, len) -> "a\tb".equals(new String(buf, offset, len))));
    assertFalse(ji.continueArray().testChars(ctx, (_, buf, offset, len) -> {
      assertEquals("a\tb", new String(buf, offset, len));
      return false;
    }));
    assertFalse(ji.continueArray().testChars(ctx, (_, _, _, _) -> true));
    assertEquals(7, ji.continueArray().readInt());
    ji.closeArray();

    final var ex = assertThrows(JsonException.class, () -> factory.create("7").testChars(ctx, (_, _, _, _) -> true));
    assertEquals("testChars", ex.op());
  }

  @Test
  void test_consume_chars() {
    final var consumed = new ArrayList<String>();
    final var ji = factory.create("[\"ab\",\"a\\tb\",null,7]");
    ji.openArray();
    ji.consumeChars((buf, offset, len) -> {
      assertTrue(buf.length > len, "unescaped value must be an in-place span");
      consumed.add(new String(buf, offset, len));
    });
    assertEquals(List.of("ab"), consumed);
    ji.continueArray().consumeChars((buf, offset, len) -> consumed.add(new String(buf, offset, len)));
    assertEquals(List.of("ab", "a\tb"), consumed);
    ji.continueArray().consumeChars((_, _, _) -> fail("null value must not invoke the consumer"));
    assertEquals(7, ji.continueArray().readInt());
    ji.closeArray();

    final var ex = assertThrows(JsonException.class, () -> factory.create("7").consumeChars((_, _, _) -> {
    }));
    assertEquals("consumeChars", ex.op());
  }

  @Test
  void test_consume_chars_context() {
    final var consumed = new ArrayList<String>();
    final var ji = factory.create("[\"ab\",\"a\\tb\",null,7]");
    ji.openArray();
    ji.consumeChars(consumed, (context, buf, offset, len) -> {
      assertTrue(buf.length > len, "unescaped value must be an in-place span");
      context.add(new String(buf, offset, len));
    });
    assertEquals(List.of("ab"), consumed);
    ji.continueArray().consumeChars(consumed, (context, buf, offset, len) -> context.add(new String(buf, offset, len)));
    assertEquals(List.of("ab", "a\tb"), consumed);
    ji.continueArray().consumeChars(consumed, (_, _, _, _) -> fail("null value must not invoke the consumer"));
    assertEquals(7, ji.continueArray().readInt());
    ji.closeArray();

    final var ex = assertThrows(JsonException.class, () -> factory.create("7").consumeChars(consumed, (_, _, _, _) -> {
    }));
    assertEquals("consumeChars", ex.op());
  }

  @Test
  void test_read_double_escaped_number_string() {
    // the escape forces the unescaping copy path of the double-valued string parse
    assertEquals(1.55d, factory.create("\"1.5\\u0035\"").readDouble());
    assertEquals(1.55f, factory.create("\"1.5\\u0035\"").readFloat());
  }

  @Test
  void test_apply_number_chars_context() {
    final var ctx = new Object();
    final var ji = factory.create("[5,678]");
    ji.openArray();
    assertEquals(5, ji.readInt());
    assertEquals("678", ji.continueArray().applyNumberChars(ctx, (context, buf, offset, len) -> {
      assertSame(ctx, context);
      return new String(buf, offset, len);
    }));
    ji.closeArray();
  }

  @Test
  void test_apply_number_chars_as_int() {
    final var ji = factory.create("[5,678]");
    ji.openArray();
    assertEquals(5, ji.readInt());
    assertEquals(678, ji.continueArray().applyNumberCharsAsInt(TestIocChars::parseInt));
    ji.closeArray();
  }

  @Test
  void test_apply_number_chars_as_int_context() {
    final var ctx = new Object();
    final var ji = factory.create("[5,678]");
    ji.openArray();
    assertEquals(5, ji.readInt());
    assertEquals(678, ji.continueArray().applyNumberCharsAsInt(ctx, (context, buf, offset, len) -> {
      assertSame(ctx, context);
      return parseInt(buf, offset, len);
    }));
    ji.closeArray();
  }

  @Test
  void test_apply_number_chars_as_long() {
    final var ji = factory.create("[5,1234567890123]");
    ji.openArray();
    assertEquals(5, ji.readInt());
    assertEquals(1234567890123L, ji.continueArray().applyNumberCharsAsLong(TestIocChars::parseLong));
    ji.closeArray();
  }

  @Test
  void test_apply_number_chars_as_long_context() {
    final var ctx = new Object();
    final var ji = factory.create("[5,1234567890123]");
    ji.openArray();
    assertEquals(5, ji.readInt());
    assertEquals(1234567890123L, ji.continueArray().applyNumberCharsAsLong(ctx, (context, buf, offset, len) -> {
      assertSame(ctx, context);
      return parseLong(buf, offset, len);
    }));
    ji.closeArray();
  }

  @Test
  void test_number_scan_stops_at_whitespace() {
    // whitespace after the first number char terminates the scan; "1 2" must
    // never splice into "12"
    final var ji = factory.create("1 2");
    assertEquals("1", ji.readNumberAsString());
    assertEquals(2, ji.readInt());
  }

  @Test
  void test_number_scan_grows_char_buffer() {
    final var ji = factory.create("123456789012345678901234567890", 4);
    assertEquals("123456789012345678901234567890", ji.readNumberAsString());
  }

  @Test
  void test_reset_across_source_types() {
    final var ji = factory.create("1");
    assertEquals(1, ji.readInt());

    assertEquals(2, ji.reset("2".getBytes()).readInt());
    assertEquals(3, ji.reset("[3]".getBytes(), 1, 2).readInt());
    assertEquals(4, ji.reset("4".toCharArray()).readInt());
    assertEquals(5, ji.reset("[5]".toCharArray(), 1, 2).readInt());
    assertEquals(6, ji.reset(new ByteArrayInputStream("6".getBytes())).readInt());
  }
}
