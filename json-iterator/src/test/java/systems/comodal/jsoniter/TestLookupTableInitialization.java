package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/// Rebuild the tables inside each test: reading the static tables alone can
/// reuse a result created before PIT activates a table-builder mutant.
final class TestLookupTableInitialization {

  @Test
  void test_hex_table_initialization() throws ReflectiveOperationException {
    final var initializer = Class.forName("systems.comodal.jsoniter.JHex$INIT_DIGITS")
        .getDeclaredMethod("initDigits");
    initializer.setAccessible(true);
    final int[] digits = (int[]) initializer.invoke(null);
    assertNotNull(digits);
    assertEquals('f' + 1, digits.length);
    for (int c = 0; c < digits.length; ++c) {
      // Character.digit is an independent oracle over this ASCII-only range.
      assertEquals(Character.digit(c, 16), digits[c], "hex digit " + c);
    }
  }

  @Test
  void test_value_type_table_initialization() throws ReflectiveOperationException {
    final var initializer = Class.forName("systems.comodal.jsoniter.ValueType$INIT_TYPES")
        .getDeclaredMethod("initValueTypes");
    initializer.setAccessible(true);
    final ValueType[] types = (ValueType[]) initializer.invoke(null);
    assertNotNull(types);
    assertEquals(256, types.length);
    for (int c = 0; c < types.length; ++c) {
      final ValueType expected = switch (c) {
        case '"' -> ValueType.STRING;
        case '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> ValueType.NUMBER;
        case 't', 'f' -> ValueType.BOOLEAN;
        case 'n' -> ValueType.NULL;
        case '[' -> ValueType.ARRAY;
        case '{' -> ValueType.OBJECT;
        default -> ValueType.INVALID;
      };
      assertEquals(expected, types[c], "JSON value-leading byte " + c);
    }
  }
}
