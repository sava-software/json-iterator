package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.FieldSource;
import systems.comodal.jsoniter.factories.JsonIteratorFactory;

import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ParameterizedClass
@FieldSource("systems.comodal.jsoniter.TestFactories#FACTORIES")
final class TestWhatIsNext {

  private final JsonIteratorFactory factory;

  TestWhatIsNext(final JsonIteratorFactory factory) {
    this.factory = factory;
  }

  @Test
  void test() {
    assertEquals(ValueType.OBJECT, factory.create("{}").whatIsNext());
    assertEquals(ValueType.STRING, factory.create("\"string\"").whatIsNext());
    assertEquals(ValueType.ARRAY, factory.create("[\"array\"]").whatIsNext());
    assertEquals(ValueType.BOOLEAN, factory.create("t").whatIsNext());
    assertEquals(ValueType.BOOLEAN, factory.create("f").whatIsNext());
    assertEquals(ValueType.NULL, factory.create("n").whatIsNext());
    assertEquals(ValueType.NUMBER, factory.create("-").whatIsNext());
    IntStream.rangeClosed(0, 9)
        .forEach(i -> assertEquals(ValueType.NUMBER, factory.create(Integer.toString(i)).whatIsNext()));
  }
}
