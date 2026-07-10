package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.FieldSource;
import systems.comodal.jsoniter.factories.JsonIteratorFactory;

import static org.junit.jupiter.api.Assertions.*;

@ParameterizedClass
@FieldSource("systems.comodal.jsoniter.TestFactories#FACTORIES")
final class TestArray {

  private final JsonIteratorFactory factory;

  TestArray(final JsonIteratorFactory factory) {
    this.factory = factory;
  }

  @Test
  void test_empty_array() {
    final var json = "[]";

    var ji = factory.create(json);
    assertFalse(ji.readArray());

    ji = factory.create(json);
    assertEquals(ji, ji.openArray());
    assertEquals(ji, ji.closeArray());
  }

  @Test
  void test_one_element() {
    final var json = "[1]";

    var ji = factory.create(json);
    assertTrue(ji.readArray());
    assertEquals(1, ji.readInt());
    assertFalse(ji.readArray());

    ji = factory.create(json);
    assertEquals(ji, ji.openArray());
    assertEquals(1, ji.readInt());
    assertEquals(ji, ji.closeArray());
  }

  @Test
  void test_two_elements() {
    final var json = " [ 1 , 2 ] ";

    var ji = factory.create(json);
    assertTrue(ji.readArray());
    assertEquals(1, ji.readInt());
    assertTrue(ji.readArray());
    assertEquals(2, ji.readInt());
    assertFalse(ji.readArray());

    ji = factory.create(json);
    assertEquals(ji, ji.openArray());
    assertEquals(1, ji.readInt());
    assertEquals(ji, ji.continueArray());
    assertEquals(2, ji.readInt());
    assertEquals(ji, ji.closeArray());
  }

  @Test
  void test_three_elements() {
    final var json = " [ 1 , 2, 3 ] ";

    var ji = factory.create(json);
    assertTrue(ji.readArray());
    assertEquals(1, ji.readInt());
    assertTrue(ji.readArray());
    assertEquals(2, ji.readInt());
    assertTrue(ji.readArray());
    assertEquals(3, ji.readInt());
    assertFalse(ji.readArray());

    ji = factory.create(json);
    assertEquals(ji, ji.openArray());
    assertEquals(1, ji.readInt());
    assertEquals(ji, ji.continueArray());
    assertEquals(2, ji.readInt());
    assertEquals(ji, ji.continueArray());
    assertEquals(3, ji.readInt());
    assertEquals(ji, ji.closeArray());
  }

  @Test
  void test_four_elements() {
    final var json = " [ 1 , 2, 3, 4 ] ";
    final var ji = factory.create(json);
    assertTrue(ji.readArray());
    assertEquals(1, ji.readInt());
    assertTrue(ji.readArray());
    assertEquals(2, ji.readInt());
    assertTrue(ji.readArray());
    assertEquals(3, ji.readInt());
    assertTrue(ji.readArray());
    assertEquals(4, ji.readInt());
    assertFalse(ji.readArray());
  }

  @Test
  void test_five_elements() {
    final var json = " [ 1 , 2, 3, 4, 5  ] ";
    final var ji = factory.create(json);
    assertTrue(ji.readArray());
    assertEquals(1, ji.readInt());
    assertTrue(ji.readArray());
    assertEquals(2, ji.readInt());
    assertTrue(ji.readArray());
    assertEquals(3, ji.readInt());
    assertTrue(ji.readArray());
    assertEquals(4, ji.readInt());
    assertTrue(ji.readArray());
    assertEquals(5, ji.readInt());
    assertFalse(ji.readArray());
  }
}
