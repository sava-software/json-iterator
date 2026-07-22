package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;
import systems.comodal.jsoniter.factory.JsonIterParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class TestJsonIterParser {

  private static final JsonIterParser<String> PARSER = JsonIterator::readString;

  @Test
  void test_parse_input_stream() throws IOException {
    assertEquals("sava", PARSER.parse(new ByteArrayInputStream("\"sava\"".getBytes())));
  }

  @Test
  void test_parse_byte_array() throws IOException {
    assertEquals("sava", PARSER.parse("\"sava\"".getBytes()));
  }

  @Test
  void test_parse_byte_array_range() throws IOException {
    final byte[] padded = "##\"sava\"##".getBytes();
    assertEquals("sava", PARSER.parse(padded, 2, padded.length - 2));
  }

  @Test
  void test_parse_string() throws IOException {
    assertEquals("sava", PARSER.parse("\"sava\""));
  }
}
