package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.FieldSource;
import systems.comodal.jsoniter.factories.JsonIteratorFactory;
import systems.comodal.jsoniter.factory.ElementFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ParameterizedClass
@FieldSource("systems.comodal.jsoniter.TestFactories#FACTORIES")
final class TestElementFactory {

  private final JsonIteratorFactory factory;

  TestElementFactory(final JsonIteratorFactory factory) {
    this.factory = factory;
  }

  private static final class ValueParser implements ElementFactory<String> {

    private String value = "";

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (JsonIterator.fieldEquals("v", buf, offset, len)) {
        value = ji.readString();
      } else {
        ji.skip();
      }
      return true;
    }

    @Override
    public String create() {
      return value;
    }
  }

  @Test
  void test_parse_list_of_objects() {
    final var ji = factory.create("[{\"v\":\"a\"},{\"v\":\"b\",\"x\":1}]");
    assertEquals(List.of("a", "b"), ElementFactory.parseList(ji, ValueParser::new));
  }

  @Test
  void test_parse_list_empty_and_null() {
    assertEquals(List.of(), ElementFactory.parseList(factory.create("[]"), ValueParser::new));
    assertEquals(List.of(), ElementFactory.parseList(factory.create("null"), ValueParser::new));
  }

  @Test
  void test_parse_list_mixed_objects_and_strings() {
    final var ji = factory.create("[{\"v\":\"a\"},\"b\",{\"v\":\"c\"}]");
    assertEquals(List.of("a", "b", "c"), ElementFactory.parseList(ji, ValueParser::new, String::new));
  }

  @Test
  void test_parse_list_with_string_parser_empty_and_null() {
    assertEquals(List.of(), ElementFactory.parseList(factory.create("[]"), ValueParser::new, String::new));
    assertEquals(List.of(), ElementFactory.parseList(factory.create("null"), ValueParser::new, String::new));
  }
}
