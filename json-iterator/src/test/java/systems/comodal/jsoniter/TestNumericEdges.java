package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.FieldSource;
import systems.comodal.jsoniter.factories.JsonIteratorFactory;

import static org.junit.jupiter.api.Assertions.*;

/// Digit-count and overflow edges of the `readInt`/`readLong` fast paths:
/// every early-exit arm of the unrolled digit ladder, the slow-path overflow
/// wrap that must reject rather than return a garbage value, and the
/// lenient quoted-number close-quote requirement.
@ParameterizedClass
@FieldSource("systems.comodal.jsoniter.TestFactories#FACTORIES")
final class TestNumericEdges {

  private final JsonIteratorFactory factory;

  TestNumericEdges(final JsonIteratorFactory factory) {
    this.factory = factory;
  }

  /// Nonzero, position-distinct digits so any mutated term of an unrolled
  /// digit formula changes the result.
  private static final String DIGITS = "2345678912345678912";

  @Test
  void test_read_int_every_digit_count() {
    // the trailing element keeps tail - head large enough to stay on the
    // unrolled fast path; each prefix length exits at a different arm
    for (int digits = 1; digits <= 9; ++digits) {
      final var expected = DIGITS.substring(0, digits);
      final var ji = factory.create("[" + expected + ",99999999999999]");
      ji.openArray();
      assertEquals(Integer.parseInt(expected), ji.readInt(), expected);
    }
    final var ji = factory.create("[1345678912,99999999999999]");
    ji.openArray();
    assertEquals(1345678912, ji.readInt());
  }

  @Test
  void test_read_long_every_digit_count() {
    for (int digits = 1; digits <= 19; ++digits) {
      final var expected = DIGITS.substring(0, digits);
      final var ji = factory.create("[" + expected + ",99999999999999]");
      ji.openArray();
      assertEquals(Long.parseLong(expected), ji.readLong(), expected);
    }
  }

  @Test
  void test_read_long_short_run_at_buffer_end() {
    // a short digit run at the very end of the buffer must take the
    // bounds-checked slow path; the fast path would read past the buffer
    final var ji = factory.create("[1111111,12");
    ji.openArray();
    assertEquals(1111111L, ji.readLong());
    assertTrue(ji.readArray());
    assertEquals(12L, ji.readLong());
  }

  @Test
  void test_int_overflow_wraps_are_rejected() {
    // 2147483649 wraps to Integer.MAX_VALUE during accumulation; the result
    // would silently negate without the post-accumulate sign check
    assertThrows(JsonException.class, () -> factory.create("2147483649").readInt());
    assertThrows(JsonException.class, () -> factory.create("-2147483649").readInt());
    assertThrows(JsonException.class, () -> factory.create("2147483648").readInt());
  }

  @Test
  void test_long_overflow_wraps_are_rejected() {
    assertThrows(JsonException.class, () -> factory.create("9223372036854775809").readLong());
    assertThrows(JsonException.class, () -> factory.create("-9223372036854775809").readLong());
    assertThrows(JsonException.class, () -> factory.create("9223372036854775808").readLong());
  }

  @Test
  void test_quoted_number_must_close_with_quote() {
    final var intEx = assertThrows(JsonException.class, () -> factory.create("\"12a\"").readInt());
    assertTrue(intEx.getMessage().contains("did not close"), intEx.getMessage());
    final var longEx = assertThrows(JsonException.class, () -> factory.create("\"12a\"").readLong());
    assertTrue(longEx.getMessage().contains("did not close"), longEx.getMessage());
  }
}
