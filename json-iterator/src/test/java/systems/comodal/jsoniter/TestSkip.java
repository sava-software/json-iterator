package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.FieldSource;
import systems.comodal.jsoniter.factories.JsonIteratorFactory;

import static org.junit.jupiter.api.Assertions.*;

@ParameterizedClass
@FieldSource("systems.comodal.jsoniter.TestFactories#FACTORIES")
final class TestSkip {

  private final JsonIteratorFactory factory;

  TestSkip(final JsonIteratorFactory factory) {
    this.factory = factory;
  }

  @Test
  void test_skip_number() {
    var ji = factory.create("[1,2]");
    assertTrue(ji.readArray());
    ji.skip();
    assertTrue(ji.readArray());
    assertEquals(2, ji.readInt());
    assertFalse(ji.readArray());
  }

  @Test
  void test_skip_string() {
    var ji = factory.create("[\"hello\",2]");
    assertTrue(ji.readArray());
    ji.skip();
    assertTrue(ji.readArray());
    assertEquals(2, ji.readInt());
    assertFalse(ji.readArray());
  }

  @Test
  void test_skip_string_streaming() {
    // Indexed factories detect the unclosed string when the index is built,
    // scalar iterators when it is skipped.
    assertThrows(JsonException.class, () -> factory.create("\"hello", 2, 2).skip());

    var ji = factory.create("\"hello\"", 2, 2);
    ji.skip();

    ji = factory.create("\"hello\"1", 2, 2);
    ji.skip();
    assertEquals(1, ji.readInt());

    ji = factory.create("\"h\\\"ello\"1", 2, 3);
    ji.skip();
    assertEquals(1, ji.readInt());

    ji = factory.create("\"\\\\\"1", 2, 3);
    ji.skip();
    assertEquals(1, ji.readInt());
  }

  @Test
  void test_skip_object() {
    var ji = factory.create("[{\"hello\": {\"world\": \"a\"}},2]");
    assertTrue(ji.readArray());
    ji.skip();
    assertTrue(ji.readArray());
    assertEquals(2, ji.readInt());
    assertFalse(ji.readArray());
  }

  @Test
  void test_skip_array() {
    var ji = factory.create("[ [1,  3] ,2]");
    assertTrue(ji.readArray());
    ji.skip();
    assertTrue(ji.readArray());
    assertEquals(2, ji.readInt());
    assertFalse(ji.readArray());
  }

  @Test
  void test_skip_nested() {
    var ji = factory.create("[ [1, {\"a\": [\"b\"] },  3] ,2]");
    assertTrue(ji.readArray());
    ji.skip();
    assertTrue(ji.readArray());
    assertEquals(2, ji.readInt());
    assertFalse(ji.readArray());
  }

  @Test
  void test_skip_large_containers() {
    // Brackets and escaped quotes inside strings must not confuse the
    // vectorized container skipping.
    final var inner = new StringBuilder("[");
    for (int i = 0; i < 300; ++i) {
      if (i > 0) {
        inner.append(',');
      }
      inner.append("{\"k").append(i).append("\":\"v ] } [ { \\\" ").append(i).append("\",\"n\":[").append(i).append(",[7,8]]}");
    }
    inner.append(']');

    var ji = factory.create("[" + inner + ",2]");
    assertTrue(ji.readArray());
    ji.skip();
    assertTrue(ji.readArray());
    assertEquals(2, ji.readInt());
    assertFalse(ji.readArray());

    ji = factory.create("{\"o\":" + inner + ",\"z\":3}");
    assertEquals(3, ji.skipUntil("z").readInt());
  }
}
