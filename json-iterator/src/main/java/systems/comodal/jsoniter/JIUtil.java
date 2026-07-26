package systems.comodal.jsoniter;

public final class JIUtil {

  private JIUtil() {
  }

  public static int fieldHashCode(final char[] value, int from, final int to) {
    int h = 0;
    while (from < to) {
      h = 31 * h + (value[from++] & 0xff);
    }
    return h;
  }

  public static int fieldCompare(final String field, final char[] buf, final int offset, final int len) {
    int i = len - field.length();
    if (i == 0) {
      for (int j = offset, c; i < len; i++, j++) {
        if ((c = Character.compare(buf[j], field.charAt(i))) != 0) {
          return c;
        }
      }
      return 0;
    } else {
      return i;
    }
  }

  public static long compileReplacePattern(final byte byteToFind) {
    final long pattern = byteToFind & 0xFFL;
    return pattern
        | (pattern << 8)
        | (pattern << 16)
        | (pattern << 24)
        | (pattern << 32)
        | (pattern << 40)
        | (pattern << 48)
        | (pattern << 56);
  }


  private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

  /// Escapes a raw string for embedding inside a JSON string literal:
  /// `"` and `\` are backslash-escaped, and control characters below 0x20
  /// are emitted as their short escape (`\n`, `\r`, `\t`, `\b`, `\f`) or
  /// a four-hex-digit unicode escape. Unlike [#escapeQuotesChecked(String)], the input is treated
  /// as raw text — a backslash already followed by a quote is itself
  /// escaped rather than interpreted as an existing escape sequence.
  /// Returns the same instance when no escaping is needed.
  ///
  /// Only `"`, `\` and 0x00-0x1F are escaped. Everything else — non-ASCII,
  /// DEL, and surrogates alike — is passed through unchanged, and UTF-16
  /// pairing is not validated: a well-formed pair survives intact, and so does
  /// a lone surrogate. Whether an unpaired one reaches its destination
  /// therefore depends on how the caller encodes the result, since UTF-8
  /// cannot represent one (Java's default encoder substitutes it silently).
  /// Callers that can receive unpaired surrogates should reject or replace
  /// them upstream. Note this differs from a well-formed `JSON.stringify`,
  /// which escapes lone surrogates instead.
  ///
  /// The input must be non-null: `null` throws a `NullPointerException`.
  /// Callers with nullable inputs choose their own null representation
  /// upstream (omit the field, emit a JSON `null`, or default to `""`) —
  /// this method does not pick one for them.
  public static String escapeJson(final String str) {
    final int len = str.length();
    int from = 0;
    while (from < len && !needsEscape(str.charAt(from))) {
      ++from;
    }
    if (from == len) {
      return str;
    }

    // Ordinary characters move in bulk (`getChars` over the span since the last
    // escape) rather than one append per character, which is what halves the
    // sparse-escape case; the escapes themselves are written straight into the
    // buffer. Measured 2026-07-25 against the previous StringBuilder version:
    // -50% on 512 chars with 8 escapes, -20% on control-heavy input, and within
    // 1% everywhere else. See EscapeBench.
    char[] out = new char[len + 8 + (len >> 3)];
    str.getChars(0, from, out, 0);
    int n = from;
    int run = from;
    for (int i = from; i < len; ++i) {
      final char c = str.charAt(i);
      if (needsEscape(c)) {
        final int span = i - run;
        // one escape expands to at most the six characters of \\u00XX
        out = ensureCapacity(out, n + span + 6);
        // unguarded: a zero-length getChars is a no-op, so `span > 0` would be
        // pure optimization — measured free, and the guard only added equivalent
        // mutants (its `>= 0` and always-true directions are indistinguishable)
        str.getChars(run, i, out, n);
        n += span;
        out[n++] = '\\';
        switch (c) {
          case '"' -> out[n++] = '"';
          case '\\' -> out[n++] = '\\';
          case '\n' -> out[n++] = 'n';
          case '\r' -> out[n++] = 'r';
          case '\t' -> out[n++] = 't';
          case '\b' -> out[n++] = 'b';
          case '\f' -> out[n++] = 'f';
          default -> {
            out[n++] = 'u';
            out[n++] = '0';
            out[n++] = '0';
            out[n++] = HEX_DIGITS[c >> 4];
            out[n++] = HEX_DIGITS[c & 0xF];
          }
        }
        run = i + 1;
      }
    }
    final int tail = len - run;
    out = ensureCapacity(out, n + tail);
    str.getChars(run, len, out, n);
    return new String(out, 0, n + tail);
  }

  /// The three characters that cannot appear raw inside a JSON string literal.
  /// Deliberately three comparisons rather than a lookup table: a guarded table
  /// (`c < TABLE.length && TABLE[c]`) measured 2.9x faster on all-lowercase text,
  /// where the guard is always false and the table never read — but 8-9% *slower*
  /// on realistic mixed-case text, where the guard becomes an unpredictable
  /// branch. These comparisons are insensitive to the alphabet.
  private static boolean needsEscape(final char c) {
    return c == '"' || c == '\\' || c < 0x20;
  }

  private static char[] ensureCapacity(final char[] out, final int needed) {
    if (needed <= out.length) {
      return out;
    }
    final var grown = new char[Math.max(needed, out.length << 1)];
    System.arraycopy(out, 0, grown, 0, out.length);
    return grown;
  }

  public static String escapeQuotesChecked(final String str) {
    final int len = str.length();
    int from = 0;
    do {
      from = str.indexOf('"', from);
      if (from < 0) {
        return str;
      }
      int i = from - 1;
      if (i < 0) {
        return escapeQuotes(str, from);
      }
      if (str.charAt(i) == '\\') {
        int escapes = 1;
        while (--i >= 0) {
          if (str.charAt(i) == '\\') {
            ++escapes;
          } else {
            break;
          }
        }
        if ((escapes & 1) == 0) {
          return escapeQuotes(str, from);
        }
      } else {
        return escapeQuotes(str, from);
      }
    } while (++from < len);
    return str;
  }

  public static String escapeQuotes(final String str) {
    return escapeQuotes(str, -1);
  }

  private static String escapeQuotes(final String str, final int firstUnescapedQuote) {
    final char[] chars = str.toCharArray();
    final char[] escaped = new char[chars.length << 1];

    int from, to;
    if (firstUnescapedQuote < 0) {
      from = 0;
      to = 0;
    } else if (firstUnescapedQuote > 0) {
      System.arraycopy(chars, 0, escaped, 0, firstUnescapedQuote);
      escaped[firstUnescapedQuote] = '\\';
      from = firstUnescapedQuote;
      to = firstUnescapedQuote + 1;
    } else {
      escaped[0] = '\\';
      from = 0;
      to = 1;
    }

    char c;
    for (int escapes = 0, dest = to; ; ++to) {
      if (to == chars.length) {
        // dest distinguishes "no escape emitted" from a flush for a quote at
        // index 0, which also leaves from == 0
        if (from == 0 && dest == 0) {
          return str;
        } else {
          final int len = to - from;
          System.arraycopy(chars, from, escaped, dest, len);
          dest += len;
          return new String(escaped, 0, dest);
        }
      } else {
        c = chars[to];
        if (c == '\\') {
          escapes++;
        } else if (c == '"' && (escapes & 1) == 0) {
          final int len = to - from;
          System.arraycopy(chars, from, escaped, dest, len);
          dest += len;
          escaped[dest++] = '\\';
          from = to;
          escapes = 0;
        } else {
          escapes = 0;
        }
      }
    }
  }

  public static String escapeQuotesRemoveNewLinesChecked(final String str) {
    final int len = str.length();
    int from = 0;
    do {
      final char c = str.charAt(from);
      if (c == '"') {
        int i = from - 1;
        if (i < 0) {
          return escapeQuotesRemoveNewLines(str, from);
        }
        if (str.charAt(i) == '\\') {
          int escapes = 1;
          while (--i >= 0) {
            if (str.charAt(i) == '\\') {
              ++escapes;
            } else {
              break;
            }
          }
          if ((escapes & 1) == 0) {
            return escapeQuotesRemoveNewLines(str, from);
          }
        } else {
          return escapeQuotesRemoveNewLines(str, from);
        }
      } else if (c == '\n' || c == '\r') {
        return escapeQuotesRemoveNewLines(str, from);
      }
    } while (++from < len);
    return str;
  }

  public static String escapeQuotesRemoveNewLines(final String str) {
    return escapeQuotesRemoveNewLines(str, -1);
  }

  private static String escapeQuotesRemoveNewLines(final String str, final int firstIdx) {
    final char[] chars = str.toCharArray();
    final char[] escaped = new char[chars.length << 1];

    int from, to, dest;
    if (firstIdx < 0) {
      from = 0;
      to = 0;
      dest = 0;
    } else {
      System.arraycopy(chars, 0, escaped, 0, firstIdx);
      if (chars[firstIdx] == '"') {
        escaped[firstIdx] = '\\';
        from = firstIdx;
        dest = firstIdx + 1;
      } else {
        from = firstIdx + 1;
        dest = firstIdx;
      }
      to = firstIdx + 1;
    }

    char c;
    for (int escapes = 0; ; ++to) {
      if (to == chars.length) {
        // dest distinguishes "nothing emitted" from a flush for a quote at
        // index 0, which also leaves from == 0
        if (from == 0 && dest == 0) {
          return str;
        } else {
          final int len = to - from;
          System.arraycopy(chars, from, escaped, dest, len);
          dest += len;
          return new String(escaped, 0, dest);
        }
      } else {
        c = chars[to];
        if (c == '\\') {
          escapes++;
        } else if (c == '"' && (escapes & 1) == 0) {
          final int len = to - from;
          System.arraycopy(chars, from, escaped, dest, len);
          dest += len;
          escaped[dest++] = '\\';
          from = to;
          escapes = 0;
        } else if (c == '\n' || c == '\r') {
          final int len = to - from;
          System.arraycopy(chars, from, escaped, dest, len);
          dest += len;
          from = to + 1;
        } else {
          escapes = 0;
        }
      }
    }
  }
}
