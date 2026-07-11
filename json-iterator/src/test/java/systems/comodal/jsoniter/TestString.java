package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.FieldSource;
import systems.comodal.jsoniter.factories.JsonIteratorFactory;

import java.util.Base64;
import java.util.Random;

import static java.lang.String.format;
import static org.junit.jupiter.api.Assertions.*;

@ParameterizedClass
@FieldSource("systems.comodal.jsoniter.TestFactories#FACTORIES")
final class TestString {

  private static final Base64.Encoder BASE64_ENCODER = Base64.getEncoder();

  private final JsonIteratorFactory factory;

  TestString(final JsonIteratorFactory factory) {
    this.factory = factory;
  }

  @Test
  void test_ascii_string() {
    var ji = factory.create("\"hello\"\"world\"");
    assertEquals("hello", ji.readString());
    assertEquals("world", ji.readString());


    final var hello = "hello".getBytes();
    final var world = "world".getBytes();
    ji = factory.create(format("\"%s\"\"%s\"", BASE64_ENCODER.encodeToString(hello), BASE64_ENCODER.encodeToString(world)));
    assertArrayEquals(hello, ji.decodeBase64String());
    assertArrayEquals(world, ji.decodeBase64String());
  }

  @Test
  void testRandomBase64Data() {
    final long seed = new Random().nextLong();
    final var random = new Random(seed);
    var data = new byte[4_096];
    random.nextBytes(data);
    var ji = factory.create(format("{\"data\":\"%s\"}", BASE64_ENCODER.encodeToString(data)));
    assertArrayEquals(data, ji.skipUntil("data").decodeBase64String(), "seed=" + seed);

    for (int len = 0; len <= 10; ++len) {
      data = new byte[len];
      random.nextBytes(data);
      ji = factory.create(format("{\"data\":\"%s\"}", BASE64_ENCODER.encodeToString(data)));
      assertArrayEquals(data, ji.skipUntil("data").decodeBase64String(), "seed=" + seed);
    }
  }

  @Test
  void test_escapes_string() {
    var ji = factory.create("\"even" + "\\".repeat(42) + '"');
    assertEquals("even" + "\\".repeat(21), ji.readString());

    ji = factory.create("\"odd" + "\\".repeat(11) + "\"\"");
    assertEquals("odd" + "\\".repeat(5) + '"', ji.readString());

    ji = factory.create("\"even\\\\\"");
    assertEquals("even\\", ji.readString());

    ji = factory.create("\"odd\\\\\\\"\"");
    assertEquals("odd\\\"", ji.readString());

    ji = factory.create("\"odd\\\"\"");
    assertEquals("odd\"", ji.readString());
  }

  @Test
  void test_ascii_string_with_escape() {
    var json = "\"he\tllo\"";
    var ji = factory.create(json);
    assertEquals("he\tllo", ji.readString());
  }

  @Test
  void test_utf8_string() {
    var ji = factory.create("\"中文\"");
    assertEquals("中文", ji.readString());
  }

  @Test
  void test_long_utf8_strings() {
    final var values = new String[]{
        "日本語テスト日本語テスト", // no 0x80 / 0x5C bytes at all
        "元野球部マネージャー❤︎…最高の夏をありがとう…", // 0x80 first appears mid-word (twitter.json)
        "中文👊中文👊中文👊", // 4-byte sequences
        "❤︎…" // short enough for the scalar path
    };
    for (final var value : values) {
      for (int pad = 0; pad < 9; ++pad) {
        final var padded = "a".repeat(pad) + value;
        final var json = "{\"data\":\"" + padded + "\",\"want\":42}";
        assertEquals(padded, factory.create(json).skipUntil("data").readString());
        assertEquals(42, factory.create(json).skipUntil("want").readInt());
      }
    }
  }

  @Test
  void test_incomplete_escape() {
    var ji = factory.create("\"\\");
    assertThrows(JsonException.class, ji::readString);
  }

  @Test
  void test_surrogate() {
    var ji = factory.create("\"👊\"");
    assertEquals("👊", ji.readString());
  }

  @Test
  void test_larger_than_buffer() {
    var ji = factory.create("\"0123456789012345678901234567890123\"");
    assertEquals("0123456789012345678901234567890123", ji.readString());
  }

  @Test
  void test_string_across_buffer() {
    var ji = factory.create("\"hello\"\"world\"", 2, 2);
    assertEquals("hello", ji.readString());
    assertEquals("world", ji.readString());
  }

  @Test
  void test_null_string() {
    var ji = factory.create("null");
    assertNull(ji.readString());
  }

  @Test
  void test_long_string() {
    var ji = factory.create("\"[\\\"LL\\\",\\\"MM\\\\\\/LW\\\",\\\"JY\\\",\\\"S\\\",\\\"C\\\",\\\"IN\\\",\\\"ME \\\\\\/ LE\\\"]\"");
    assertEquals("[\"LL\",\"MM\\/LW\",\"JY\",\"S\",\"C\",\"IN\",\"ME \\/ LE\"]", ji.readString());
  }
}
