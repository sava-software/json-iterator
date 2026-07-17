package systems.comodal.jsoniter;

import java.util.Arrays;

public enum ValueType {

  INVALID,
  STRING,
  NUMBER,
  NULL,
  BOOLEAN,
  ARRAY,
  OBJECT;

  static final ValueType[] VALUE_TYPES = INIT_TYPES.initValueTypes();

  /// The table covers the byte range; a chars source can peek any char, and
  /// anything past the table is by definition not a value-leading token.
  static ValueType of(final char c) {
    return c < VALUE_TYPES.length ? VALUE_TYPES[c] : INVALID;
  }

  private static final class INIT_TYPES {

    private INIT_TYPES() {
    }

    private static ValueType[] initValueTypes() {
      final ValueType[] types = new ValueType[256];
      Arrays.fill(types, INVALID);
      types['"'] = STRING;
      types['-'] = NUMBER;
      types['0'] = NUMBER;
      types['1'] = NUMBER;
      types['2'] = NUMBER;
      types['3'] = NUMBER;
      types['4'] = NUMBER;
      types['5'] = NUMBER;
      types['6'] = NUMBER;
      types['7'] = NUMBER;
      types['8'] = NUMBER;
      types['9'] = NUMBER;
      types['t'] = BOOLEAN;
      types['f'] = BOOLEAN;
      types['n'] = NULL;
      types['['] = ARRAY;
      types['{'] = OBJECT;
      return types;
    }
  }
}
