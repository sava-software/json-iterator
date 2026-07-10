package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class TestIO {

  @Test
  void printJavaRuntimeVersion(final TestReporter reporter) {
    reporter.publishEntry("java.runtime.version", Runtime.version().toString());
  }

  @Test
  void test_read_byte() {
    final var ji = (BufferedStreamJsonIterator) JsonIterator.parse(new ByteArrayInputStream("1".getBytes()), 64);
    assertEquals('1', ji.read());
    assertThrows(JsonException.class, ji::read);
  }

  @Test
  void test_read_bytes() {
    final var ji = (BufferedStreamJsonIterator) JsonIterator.parse(new ByteArrayInputStream("12".getBytes()), 64);
    assertEquals('1', ji.read());
    assertEquals('2', ji.read());
    assertThrows(JsonException.class, ji::read);
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
}
