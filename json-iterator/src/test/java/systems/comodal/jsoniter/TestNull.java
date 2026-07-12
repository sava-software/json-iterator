package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.FieldSource;
import systems.comodal.jsoniter.factories.JsonIteratorFactory;

import static java.lang.Boolean.TRUE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@ParameterizedClass
@FieldSource("systems.comodal.jsoniter.TestFactories#FACTORIES")
final class TestNull {

  private final JsonIteratorFactory factory;

  TestNull(final JsonIteratorFactory factory) {
    this.factory = factory;
  }

  @Test
  void test_null_as_String() {
    var ji = factory.create("{\"field\":null}");
    ji.skipObjField();
    assertNull(ji.readString());

    ji = factory.create("{\"field\":null}");
    assertNull(ji.applyObject(TRUE, ((context, buf, offset, len, jsonIterator) -> {
      assertEquals("field", new String(buf, offset, len));
      assertEquals(TRUE, context);
      return jsonIterator.readString();
    })));
  }

  @Test
  void test_null_as_Object() {
    var ji = factory.create("{\"field\":null}");
    ji.skipObjField();
    assertNull(ji.skipObjField());
  }

  @Test
  void test_null_as_BigDecimal() {
    var ji = factory.create("{\"field\":null}");
    ji.skipObjField();
    assertNull(ji.readBigDecimal());
  }

  @Test
  void test_null_as_BigInteger() {
    var ji = factory.create("{\"field\":null}");
    ji.skipObjField();
    assertNull(ji.readBigInteger());
  }
}
