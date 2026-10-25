package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.FieldSource;
import systems.comodal.jsoniter.factories.JsonIteratorFactory;

import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

@ParameterizedClass
@FieldSource("systems.comodal.jsoniter.TestFactories#FACTORIES")
final class TestIteratorContracts {

  private final JsonIteratorFactory factory;

  TestIteratorContracts(final JsonIteratorFactory factory) {
    this.factory = factory;
  }

  @Test
  void test_array_separators_are_validated() {
    for (final var token : new String[]{"1", "}", ":", "null"}) {
      assertThrows(JsonException.class, () -> factory.create(token).continueArray(), token);
      assertThrows(JsonException.class, () -> factory.create(token).closeArray(), token);
    }
    assertThrows(JsonException.class, () -> factory.create("]").continueArray());
    assertThrows(JsonException.class, () -> factory.create(",").closeArray());
  }

  @Test
  void test_array_null_is_validated_and_consumed() {
    final var ji = factory.create("null 7");
    assertFalse(ji.readArray());
    assertEquals(7, ji.readInt());
    for (final var token : new String[]{"xull", "nulx", "true", "{}", "1"}) {
      assertThrows(JsonException.class, () -> factory.create(token).readArray(), token);
    }
  }

  @Test
  void test_skip_returns_the_iterator_at_the_next_value() {
    for (final var value : new String[]{"null", "true", "false", "12", "\"text\"", "[]", "{}"}) {
      final var ji = factory.create(value + " 7");
      assertSame(ji, ji.skip(), value);
      assertEquals(7, ji.readInt(), value);
    }
  }

  @Test
  void test_object_skip_ignores_structure_inside_strings() {
    for (final var json : new String[]{
        "{\"}\":0} 7",
        "{\"a\":\"}\"} 7",
        "{\"a\":\"{\",\"b\":0} 7",
        "{\"a\":\"\\\"}\\\"\"} 7"
    }) {
      final var ji = factory.create(json);
      ji.skip();
      assertEquals(7, ji.readInt(), json);
    }
  }

  private void assertWrongNumericType(final String json,
                                      final String op,
                                      final Function<JsonIterator, ?> read) {
    final var ex = assertThrows(JsonException.class, () -> read.apply(factory.create(json)), json);
    // JsonException.op identifies the public read that rejected the value.
    assertEquals(op, ex.op(), json);
  }

  @Test
  void test_numeric_reads_reject_other_value_types() {
    for (final var json : new String[]{"true", "[]", "{}"}) {
      assertWrongNumericType(json, "readDouble", JsonIterator::readDouble);
      assertWrongNumericType(json, "readFloat", JsonIterator::readFloat);
      assertWrongNumericType(json, "readBigDecimal", JsonIterator::readBigDecimal);
      assertWrongNumericType(json, "readBigDecimal", JsonIterator::readBigDecimalDropZeroes);
      assertWrongNumericType(json, "readBigInteger", JsonIterator::readBigInteger);
    }
  }

  @Test
  void test_big_decimal_null_consumes_the_value() {
    var ji = factory.create("null 7");
    assertNull(ji.readBigDecimal());
    assertEquals(7, ji.readInt());
    ji = factory.create("null 7");
    assertNull(ji.readBigDecimalDropZeroes());
    assertEquals(7, ji.readInt());
  }

  @Test
  void test_field_search_validates_each_separator() {
    for (final var json : new String[]{"{\"a\":0,b:1}", "{\"a\":0,xb\":1}", "{\"a\":0,\"b\" 1}", "true", "]", "1"}) {
      assertThrows(JsonException.class, () -> factory.create(json).skipUntil("b"), json);
    }
  }

  @Test
  void test_field_search_compares_decoded_names() {
    assertEquals(1, factory.create("{\"a\\u0062\":1,\"ab\":2}").skipUntil("ab").readInt());
    assertEquals(2, factory.create("{\"a\\u0062\":1,\"ax\":2}").skipUntil("ax").readInt());
    assertNull(factory.create("{\"a\\u0062\":1}").skipUntil("ax"));
  }

  @Test
  void test_matcher_rejects_truncated_names_across_word_boundaries() {
    final var matcher = FieldMatcher.of("name");
    for (int length = 0; length <= 40; ++length) {
      final var json = "{\"" + "a".repeat(length);
      assertThrows(JsonException.class,
          () -> factory.create(json).testObject(matcher, (_, _) -> fail("truncated name reached callback")),
          json);
    }
  }

  @Test
  void test_carriage_return_is_json_whitespace() {
    assertEquals(7, factory.create("\r7").readInt());
    assertEquals(7, factory.create("\r\n\t 7").readInt());
  }

  @Test
  void test_non_ascii_leading_token_is_invalid() {
    assertEquals(ValueType.INVALID, factory.create("Ā").whatIsNext());
  }
}
