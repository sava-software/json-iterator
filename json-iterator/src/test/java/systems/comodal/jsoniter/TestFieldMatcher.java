package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.FieldSource;
import systems.comodal.jsoniter.factories.JsonIteratorFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ParameterizedClass
@FieldSource("systems.comodal.jsoniter.TestFactories#FACTORIES")
final class TestFieldMatcher {

  private final JsonIteratorFactory factory;

  TestFieldMatcher(final JsonIteratorFactory factory) {
    this.factory = factory;
  }

  @Test
  void test_match() {
    final var matcher = FieldMatcher.of(
        "a", "ab", "abcdefgh", "abcdefghi", "abcdefghijklmnop", "abcdefghijklmnopq",
        // Same length, same first and last eight bytes — differ only in the
        // middle, forcing the verifying comparison to disambiguate.
        "prefix--MIDDLE--suffix", "prefix--CENTER--suffix"
    );
    assertEquals(8, matcher.numFields());
    for (int i = 0; i < matcher.numFields(); ++i) {
      final byte[] name = matcher.name(i).getBytes(StandardCharsets.UTF_8);
      assertEquals(i, matcher.match(name, 0, name.length), matcher.name(i));
      // Same bytes at a non-zero offset.
      final byte[] padded = ("xx" + matcher.name(i) + "yy").getBytes(StandardCharsets.UTF_8);
      assertEquals(i, matcher.match(padded, 2, name.length), matcher.name(i));
    }
    for (final var unknown : List.of("", "b", "abc", "abcdefghj", "prefix--MIDDLE--suffiy", "zzzzzzzzzzzzzzzzzzzzzzzzzz")) {
      final byte[] name = unknown.getBytes(StandardCharsets.UTF_8);
      assertEquals(-1, matcher.match(name, 0, name.length), unknown);
    }
  }

  @Test
  void test_char_match_agrees_with_byte_match() {
    final var matcher = FieldMatcher.of("a", "field1", "abcdefgh", "abcdefghijklmnopq", "поле");
    for (int i = 0; i < matcher.numFields(); ++i) {
      final var name = matcher.name(i);
      final byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
      final char[] chars = name.toCharArray();
      assertEquals(i, matcher.match(bytes, 0, bytes.length), name);
      assertEquals(i, matcher.match(chars, 0, chars.length), name);
    }
    final char[] unknown = "field2".toCharArray();
    assertEquals(-1, matcher.match(unknown, 0, unknown.length));
  }

  @Test
  void test_char_narrowing_confusables_do_not_match() {
    // The chars 'Ã' (U+00C3) and '©' (U+00A9) narrow to the UTF-8 bytes of
    // "é" — a char-level match must not conflate them.
    final var matcher = FieldMatcher.of("é");
    final char[] confusable = "Ã©".toCharArray();
    assertEquals(-1, matcher.match(confusable, 0, confusable.length));
    final char[] actual = "é".toCharArray();
    assertEquals(0, matcher.match(actual, 0, actual.length));
  }

  @Test
  void test_duplicate_names_rejected() {
    assertThrows(IllegalArgumentException.class, () -> FieldMatcher.of("a", "b", "a"));
  }

  @Test
  void test_field_index_dispatch() {
    final var matcher = FieldMatcher.of("field1", "field2");
    final var ji = factory.create("""
        {"field1":1,"unknown":{"field1":42},"field2":2}""");
    final long[] values = new long[2];
    ji.testObject(matcher, (fieldIndex, jsonIterator) -> {
          switch (fieldIndex) {
            case 0 -> values[0] = jsonIterator.readLong();
            case 1 -> values[1] = jsonIterator.readLong();
            default -> jsonIterator.skip(); // the nested "field1" must not leak through
          }
          return true;
        }
    );
    assertArrayEquals(new long[]{1, 2}, values);
  }

  @Test
  void test_context_dispatch_and_break_out() {
    final var matcher = FieldMatcher.of("b");
    final var ji = factory.create("""
        {"a":1,"b":2,"c":3}""");
    final var seen = new ArrayList<Integer>();
    ji.testObject(seen, matcher, (context, fieldIndex, jsonIterator) -> {
          context.add(fieldIndex);
          jsonIterator.skip();
          return fieldIndex != 0;
        }
    );
    assertEquals(List.of(-1, 0), seen);
  }

  @Test
  void test_escaped_and_multibyte_names_match() {
    final var matcher = FieldMatcher.of("fie\"ld", "поле", "tab");
    final var ji = factory.create("""
        {"fie\\"ld":1,"поле":2,"t\\u0061b":3,"other":4}""");
    final long[] values = new long[3];
    ji.testObject(matcher, (fieldIndex, jsonIterator) -> {
          if (fieldIndex >= 0) {
            values[fieldIndex] = jsonIterator.readLong();
          } else {
            jsonIterator.skip();
          }
          return true;
        }
    );
    assertArrayEquals(new long[]{1, 2, 3}, values);
  }

  @Test
  void test_extended_matcher_preserves_base_indices() {
    final var base = FieldMatcher.of("name", "kind");
    final var extended = FieldMatcher.of(base, "docs", "type");
    assertEquals(4, extended.numFields());
    for (int i = 0; i < base.numFields(); ++i) {
      assertEquals(base.name(i), extended.name(i));
      final byte[] name = base.name(i).getBytes(StandardCharsets.UTF_8);
      assertEquals(base.match(name, 0, name.length), extended.match(name, 0, name.length));
    }
    final byte[] docs = "docs".getBytes(StandardCharsets.UTF_8);
    assertEquals(2, extended.match(docs, 0, docs.length));
    assertThrows(IllegalArgumentException.class, () -> FieldMatcher.of(base, "kind"));
  }

  @Test
  void test_match_string_value() {
    final var matcher = FieldMatcher.of("processed", "confirmed", "finalized");
    final var ji = factory.create("""
        ["confirmed", "finalized", "processed", "unknown", null, "поле"]""");
    assertTrue(ji.readArray());
    assertEquals(1, ji.matchString(matcher));
    assertTrue(ji.readArray());
    assertEquals(2, ji.matchString(matcher));
    assertTrue(ji.readArray());
    assertEquals(0, ji.matchString(matcher));
    assertTrue(ji.readArray());
    assertEquals(-1, ji.matchString(matcher));
    assertTrue(ji.readArray());
    assertEquals(-1, ji.matchString(matcher)); // null
    assertTrue(ji.readArray());
    assertEquals(-1, ji.matchString(matcher)); // multi-byte unknown
    assertFalse(ji.readArray());

    final var ji2 = factory.create("[42]");
    ji2.readArray();
    assertThrows(JsonException.class, () -> ji2.matchString(matcher));
  }

  @Test
  void test_match_string_escaped_and_multibyte() {
    final var matcher = FieldMatcher.of("base64+zstd", "поле", "tab");
    final var ji = factory.create("""
        ["base64+zstd", "поле", "t\\u0061b"]""");
    assertTrue(ji.readArray());
    assertEquals(0, ji.matchString(matcher));
    assertTrue(ji.readArray());
    assertEquals(1, ji.matchString(matcher));
    assertTrue(ji.readArray());
    assertEquals(2, ji.matchString(matcher));
    assertFalse(ji.readArray());
  }

  @Test
  void test_masked_dispatch_breaks_out_when_complete() {
    final var matcher = FieldMatcher.of("fee", "computeUnitsConsumed");
    final var ji = factory.create("""
        {"other":1,"fee":5000,"computeUnitsConsumed":150,"never":{"fee":1}}""");
    final long[] values = new long[2];
    final long all = (1L << matcher.numFields()) - 1;
    ji.testObject(values, matcher, (context, mask, fieldIndex, jsonIterator) -> {
      switch (fieldIndex) {
        case 0 -> context[0] = jsonIterator.readLong();
        case 1 -> context[1] = jsonIterator.readLong();
        default -> {
          assertTrue(fieldIndex < 0, "unexpected known field after break-out");
          jsonIterator.skip();
          return mask;
        }
      }
      final long seen = mask | (1L << fieldIndex);
      return seen == all ? ContextFieldIndexMaskedPredicate.BREAK_OUT : seen;
    });
    assertArrayEquals(new long[]{5000, 150}, values);
    // The break-out leaves the iterator inside the object; the caller skips the rest.
    ji.skipRestOfObject();
  }

  @Test
  void test_empty_and_null_object() {
    final var matcher = FieldMatcher.of("a");
    factory.create("{}").testObject(matcher, (_, _) -> {
          throw new AssertionError("no fields expected");
        }
    );
    factory.create("null").testObject(matcher, (_, _) -> {
          throw new AssertionError("no fields expected");
        }
    );
  }

  private enum Mode {
    ExactIn, ExactOut
  }

  private enum Encoding {
    BASE_64("base64"), BASE_64_ZSTD("base64+zstd");

    private final String wireName;

    Encoding(final String wireName) {
      this.wireName = wireName;
    }

    String wireName() {
      return wireName;
    }
  }

  @Test
  void test_enum_matcher_by_name() {
    final var parser = FieldMatcher.enumMatcher(Mode.values());

    assertEquals(Mode.ExactIn, factory.create("\"ExactIn\"").applyChars(parser));
    assertEquals(Mode.ExactOut, factory.create("\"ExactOut\"").applyChars(parser));
    assertNull(factory.create("\"exactin\"").applyChars(parser));
    assertNull(factory.create("\"unknown\"").applyChars(parser));
    assertNull(factory.create("null").applyChars(parser));
  }

  @Test
  void test_enum_matcher_wire_names() {
    final var parser = FieldMatcher.enumMatcher(Encoding.values(), Encoding::wireName);

    assertEquals(Encoding.BASE_64, factory.create("\"base64\"").applyChars(parser));
    assertEquals(Encoding.BASE_64_ZSTD, factory.create("\"base64+zstd\"").applyChars(parser));
    assertNull(factory.create("\"BASE_64\"").applyChars(parser));

    final var ji = factory.create("{\"encoding\":\"base64+zstd\",\"after\":1}");
    assertEquals(Encoding.BASE_64_ZSTD, ji.skipUntil("encoding").applyChars(parser));
    assertEquals(1, ji.skipUntil("after").readInt());
  }

  @Test
  void test_enum_matcher_duplicate_wire_names() {
    assertThrows(IllegalArgumentException.class, () -> FieldMatcher.enumMatcher(Mode.values(), _ -> "same"));
  }
}
