package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.*;

final class TestIO {

  @Test
  void printJavaRuntimeVersion(final TestReporter reporter) {
    reporter.publishEntry("java.runtime.version", Runtime.version().toString());
  }

  @Test
  void test_read_byte() {
    final var ji = (BytesJsonIterator) JsonIterator.parse(new ByteArrayInputStream("1".getBytes()), 64);
    assertEquals('1', ji.read());
    assertThrows(JsonException.class, ji::nextToken);
  }

  @Test
  void test_read_bytes() {
    final var ji = (BytesJsonIterator) JsonIterator.parse(new ByteArrayInputStream("12".getBytes()), 64);
    assertEquals('1', ji.read());
    assertEquals('2', ji.read());
    assertThrows(JsonException.class, ji::nextToken);
  }

  @Test
  void test_stream_read_fully_and_closed() {
    final boolean[] closed = {false};
    final var in = new ByteArrayInputStream("[1,2]".getBytes()) {
      @Override
      public void close() {
        closed[0] = true;
      }
    };
    final var ji = JsonIterator.parse(in);
    assertTrue(closed[0]);
    final int mark = ji.mark();
    assertTrue(ji.readArray());
    assertEquals(1, ji.readInt());
    assertTrue(ji.readArray());
    assertEquals(2, ji.readInt());
    assertFalse(ji.readArray());
    ji.reset(mark);
    assertTrue(ji.readArray());
    assertEquals(1, ji.readInt());
  }

  @Test
  void test_sub_range_reuse_with_mark_reset() {
    // The websocket-router shape: one reused iterator, reset onto a sub-range
    // of a larger buffer whose content beyond tail is stale, with mark/reset
    // to revisit out-of-order fields. Nothing past tail may be read.
    final var doc = "{\"b\":2,\"a\":1}";
    final var padded = " " + doc + "{\"stale\":9}";
    final int head = 1;
    final int tail = head + doc.length();

    final byte[] bytes = padded.getBytes();
    var ji = JsonIterator.parse("[]").reset(bytes, head, tail);
    final int mark = ji.mark();
    assertEquals(1, ji.skipUntil("a").readInt());
    ji.reset(mark);
    assertEquals(2, ji.skipUntil("b").readInt());
    // the sub-range ends after the document: seeking a missing field must not
    // walk into the stale content beyond tail
    ji.reset(mark);
    assertNull(ji.skipUntil("stale"));

    final char[] chars = padded.toCharArray();
    ji = JsonIterator.parse(new char[]{'[', ']'}).reset(chars, head, tail);
    final int charMark = ji.mark();
    assertEquals(1, ji.skipUntil("a").readInt());
    ji.reset(charMark);
    assertEquals(2, ji.skipUntil("b").readInt());
    ji.reset(charMark);
    assertNull(ji.skipUntil("stale"));

    // a sub-range cut mid-field rejects instead of completing from the stale
    // content beyond tail
    final var truncated = JsonIterator.parse(bytes, head, tail - 5);
    assertThrows(JsonException.class, () -> truncated.skipUntil("a"));
    final var truncatedChars = JsonIterator.parse(chars, head, tail - 5);
    assertThrows(JsonException.class, () -> truncatedChars.skipUntil("a"));
  }

  @Test
  void test_utf8() {
    byte[] bytes = {'"', (byte) 0xe4, (byte) 0xb8, (byte) 0xad, (byte) 0xe6, (byte) 0x96, (byte) 0x87, '"'};
    var ji = JsonIterator.parse(new ByteArrayInputStream(bytes), 2);
    assertEquals("中文", ji.readString());

    ji = JsonIterator.parse(bytes);
    assertEquals("中文", ji.readString());
  }

  @Test
  void test_normal_escape() {
    byte[] bytes = {'"', (byte) '\\', (byte) 't', '"'};
    var ji = JsonIterator.parse(new ByteArrayInputStream(bytes), 2);
    assertEquals("\t", ji.readString());

    ji = JsonIterator.parse(bytes);
    assertEquals("\t", ji.readString());
  }

  @Test
  void test_unicode_escape() {
    byte[] bytes = {'"', (byte) '\\', (byte) 'u', (byte) '4', (byte) 'e', (byte) '2', (byte) 'd', '"'};
    var ji = JsonIterator.parse(new ByteArrayInputStream(bytes), 2);
    assertEquals("中", ji.readString());

    ji = JsonIterator.parse(bytes);
    assertEquals("中", ji.readString());
  }

  @Test
  void test_reset_with_stream() {
    final var ji = JsonIterator.parse(new ByteArrayInputStream("1".getBytes()), 64);
    assertEquals(1, ji.readInt());
    final var reset = ji.reset(new ByteArrayInputStream("2".getBytes()));
    assertEquals(2, reset.readInt());
  }
}
