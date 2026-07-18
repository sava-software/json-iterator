package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Pins the full JHex digit table through decode. Note that mutants inside
/// INIT_DIGITS.initDigits itself are unkillable under PIT: the table is built
/// in JHex's static initializer, which runs once per PIT minion JVM, so
/// mutants executed later in a minion's batch run against the already-built
/// table.
final class TestJHex {

  @Test
  void testDecodeAllValidDigits() {
    final var lower = "0123456789abcdef";
    for (int i = 0; i < lower.length(); ++i) {
      assertEquals(i, JHex.decode(lower.charAt(i)), lower.substring(i, i + 1));
    }
    final var upper = "0123456789ABCDEF";
    for (int i = 0; i < upper.length(); ++i) {
      assertEquals(i, JHex.decode(upper.charAt(i)), upper.substring(i, i + 1));
    }
  }

  @Test
  void testDecodeRejectsAllOtherValues() {
    // Sources index with raw values: negative bytes from multibyte content and
    // chars beyond 'f' must all reject, including the range-interior
    // non-digits (':'..'@', 'G'..'`') that only the table marks invalid.
    for (int b = -128; b < 256; ++b) {
      final boolean valid = (b >= '0' && b <= '9') || (b >= 'a' && b <= 'f') || (b >= 'A' && b <= 'F');
      if (!valid) {
        final int value = b;
        assertThrows(JsonException.class, () -> JHex.decode(value), () -> "decode(" + value + ")");
      }
    }
  }
}
