package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.FieldSource;
import systems.comodal.jsoniter.factories.JsonIteratorFactory;

import static org.junit.jupiter.api.Assertions.*;

@ParameterizedClass
@FieldSource("systems.comodal.jsoniter.TestFactories#FACTORIES")
final class TestBoolean {

  private final JsonIteratorFactory factory;

  TestBoolean(final JsonIteratorFactory factory) {
    this.factory = factory;
  }

  @Test
  void test_boolean_array() {
    final var json = "[true,false,null,true]";
    var ji = factory.create(json);
    ji.readArray();
    assertTrue(ji.readBoolean());
    ji.readArray();
    assertFalse(ji.readBoolean());
    ji.readArray();
    assertTrue(ji.readNull());
    ji.readArray();
    assertTrue(ji.readBoolean());
    assertFalse(ji.readArray());

    ji = factory.create(json);
    assertTrue(ji.openArray().readBoolean());
    assertFalse(ji.continueArray().readBoolean());
    assertTrue(ji.continueArray().readNull());
    assertTrue(ji.continueArray().readBoolean());
    assertNotNull(ji.closeArray());
  }

  @Test
  void test_booleans() {
    assertTrue(factory.create("true").readBoolean());
    assertFalse(factory.create("false").readBoolean());
    assertTrue(factory.create("null").readNull());
    assertFalse(factory.create("true").readNull());
    assertFalse(factory.create("false").readNull());
  }
}
