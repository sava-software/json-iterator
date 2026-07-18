package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static systems.comodal.jsoniter.JsonIterator.*;

/// The static field-span helpers on [JsonIterator], including the
/// length-mismatch guards and case-fold edges PIT flagged as unexercised.
final class TestFieldHelpers {

  private static char[] chars(final String value) {
    return value.toCharArray();
  }

  @Test
  void test_field_equals() {
    assertTrue(fieldEquals("abc", chars("abc")));
    assertFalse(fieldEquals("abc", chars("abd")));
    assertTrue(fieldEquals("ab", chars("abz"), 2));
    // length mismatch must fail before any chars compare
    assertFalse(fieldEquals("abc", chars("ab"), 2));
    assertFalse(fieldEquals("ab", chars("abc"), 3));
    assertTrue(fieldEquals("bc", chars("abcd"), 1, 2));
    assertFalse(fieldEquals("bc", chars("abcd"), 0, 2));
  }

  @Test
  void test_field_equals_ignore_case() {
    assertTrue(fieldEqualsIgnoreCase("aBc", chars("AbC")));
    assertFalse(fieldEqualsIgnoreCase("abc", chars("abd")));
    assertTrue(fieldEqualsIgnoreCase("ab", chars("ABz"), 2));
    assertFalse(fieldEqualsIgnoreCase("abc", chars("ab"), 2));
    assertTrue(fieldEqualsIgnoreCase("bC", chars("aBcd"), 1, 2));
    // titlecase ǅ upper- and lower-cases away from itself: identical chars
    // must match without consulting the case folds
    assertTrue(fieldEqualsIgnoreCase("ǅ", chars("ǅ")));
  }

  @Test
  void test_field_starts_with() {
    final var buf = chars("commissionBps");
    assertTrue(fieldStartsWith("comm", buf, 0, buf.length));
    assertFalse(fieldStartsWith("comN", buf, 0, buf.length));
    // exact length is a valid prefix; one longer cannot match
    assertTrue(fieldStartsWith("commissionBps", buf, 0, buf.length));
    assertFalse(fieldStartsWith("commissionBpsX", buf, 0, buf.length));
    assertTrue(fieldStartsWith("miss", chars("xxmissionBps"), 2, 10));
    assertFalse(fieldStartsWith("miss", chars("xxmissionBps"), 3, 9));
  }

  @Test
  void test_field_starts_with_ignore_case() {
    final var buf = chars("commissionBps");
    assertTrue(fieldStartsWithIgnoreCase("COMM", buf, 0, buf.length));
    assertFalse(fieldStartsWithIgnoreCase("COMN", buf, 0, buf.length));
    assertTrue(fieldStartsWithIgnoreCase("commissionbps", buf, 0, buf.length));
    assertFalse(fieldStartsWithIgnoreCase("commissionbpsx", buf, 0, buf.length));
    assertTrue(fieldStartsWithIgnoreCase("MISS", chars("xxmissionBps"), 2, 10));
    // both case-fold directions: lower field over upper buffer needs the
    // toUpperCase comparison, and vice versa
    assertTrue(fieldStartsWithIgnoreCase("comm", chars("COMMISSIONBPS"), 0, 13));
    assertTrue(fieldStartsWithIgnoreCase("ǅx", chars("ǅxy"), 0, 3));
  }

  @Test
  void test_field_ends_with() {
    final var buf = chars("commissionBps");
    assertTrue(fieldEndsWith("Bps", buf, 0, buf.length));
    assertFalse(fieldEndsWith("bps", buf, 0, buf.length));
    // exact length is a valid suffix; one longer must fail on the length
    // guard even when its tail matches the whole span
    assertTrue(fieldEndsWith("commissionBps", buf, 0, buf.length));
    assertFalse(fieldEndsWith("xcommissionBps", buf, 0, buf.length));
    // span-relative: [2, 9) is "mission"
    assertTrue(fieldEndsWith("sion", chars("xxmissionyy"), 2, 7));
    assertTrue(fieldEndsWith("mission", chars("xxmissionyy"), 2, 7));
    assertFalse(fieldEndsWith("sionx", chars("xxmissionyy"), 2, 7));
  }

  @Test
  void test_field_ends_with_ignore_case() {
    final var buf = chars("commissionBps");
    assertTrue(fieldEndsWithIgnoreCase("bPS", buf, 0, buf.length));
    assertFalse(fieldEndsWithIgnoreCase("xPS", buf, 0, buf.length));
    assertTrue(fieldEndsWithIgnoreCase("commissionbps", buf, 0, buf.length));
    assertFalse(fieldEndsWithIgnoreCase("xcommissionbps", buf, 0, buf.length));
    assertTrue(fieldEndsWithIgnoreCase("SION", chars("xxmissionyy"), 2, 7));
    assertTrue(fieldEndsWithIgnoreCase("MISSION", chars("xxmissionyy"), 2, 7));
    // both case-fold directions and the titlecase identity edge
    assertTrue(fieldEndsWithIgnoreCase("bps", chars("COMMISSIONBPS"), 0, 13));
    assertTrue(fieldEndsWithIgnoreCase("xǅ", chars("yxǅ"), 0, 3));
  }

  @Test
  void test_parse_sub_range_overloads() {
    final byte[] padded = "xx{\"a\":1}yy".getBytes();
    assertEquals(1, JsonIterator.parse(padded, 2, 9).skipUntil("a").readInt());
    assertEquals(1, JsonIterator.parse(padded, 2, 9, 16).skipUntil("a").readInt());
    assertEquals(2, JsonIterator.parse("{\"b\":2}", 16).skipUntil("b").readInt());
  }
}
