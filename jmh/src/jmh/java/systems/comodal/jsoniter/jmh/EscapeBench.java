package systems.comodal.jsoniter.jmh;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import systems.comodal.jsoniter.JIUtil;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/// Prices `JIUtil.escapeJson` against candidate rewrites. The variants form a
/// chain from the shipped implementation to the most-rewritten candidate, each
/// step changing **exactly one** variable, so a win is attributable to a
/// specific decision rather than to a bundle of them:
///
/// | variant | scan loop | scan test | emit |
/// |---|---|---|---|
/// | `current` | do/while | 3-comparison | per-char |
/// | `runs` | do/while | 3-comparison | bulk-run |
/// | `runsClean` | while/break | 3-comparison | bulk-run |
/// | `runsTable` | while/break | lookup table | bulk-run |
/// | `chars` | while/break | lookup table | grown `char[]` |
///
/// So `runs - current` is the emit rewrite, `runsClean - runs` is the loop
/// restructure, `runsTable - runsClean` is the scan test, and `chars -
/// runsTable` is the output buffer.
///
/// The **loop restructure** is not only a performance question. The shipped
/// scan ends `... && ++from >= 0`, whose comparison is vacuous — `from` only
/// ever increments, so the test is `>= 1` by the time it runs, and only the
/// `++from` side effect is load-bearing. That dead comparison is what leaves
/// two equivalent mutants on the line in the `util` baseline; `while/break`
/// spells the same scan without it, so the rows go away by refactor instead of
/// by acceptance.
///
/// The two costs the shapes separate: every input pays the **scan**, and a
/// clean input pays *only* it (the method returns the same instance without
/// allocating), which is what `clean_*` isolates. The **emit loop** is reached
/// only once a first special character exists, and `sparse` — long, mostly
/// ordinary characters, a handful of escapes — is the shape that separates
/// per-char emit from bulk-run emit.
///
/// The table is indexed only after a range check (`c < SPECIAL.length`), per
/// the never-index-a-table-with-a-raw-source-value rule in AGENTS.md.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class EscapeBench {

  /// The alphabet matters as much as the escape density, and not obviously.
  /// The table scan's guard is `c < SPECIAL.length`, i.e. `c < 0x5D`, so for
  /// lowercase letters (0x61-0x7A) the test short-circuits on **one comparison
  /// and never reads the table**. An all-lowercase input therefore prices a
  /// single compare against the shipped three, which flatters the table variant
  /// for a reason that has nothing to do with the lookup. `mixed_long` and
  /// `low_long` exist to price the characters that fall *below* the guard —
  /// spaces (0x20), digits (0x30-0x39), uppercase (0x41-0x5A), punctuation —
  /// where the table actually gets read.
  @Param({"clean_short", "clean_long", "mixed_long", "low_long", "sparse", "dense", "controls"})
  private String shape;

  private String input;

  @Setup
  public void setup() {
    input = switch (shape) {
      // the common case: a short clean value, scan then return the same instance
      case "clean_short" -> ascii(24, 0, 0);
      // the same path with enough length for the scan loop to dominate.
      // NB all-lowercase, so every character sits above the table guard
      case "clean_long" -> ascii(512, 0, 0);
      // realistic JSON-value text: mixed case, digits, spaces, punctuation, so
      // a large fraction falls below the guard and does reach the table
      case "mixed_long" -> fromPool(512, MIXED_POOL);
      // worst case for the guard: every character below 0x5D, so it never
      // short-circuits and the table is read on every position
      case "low_long" -> fromPool(512, LOW_POOL);
      // long, mostly ordinary, a handful of quotes/backslashes — the shape that
      // separates per-char emit from run-bulk emit
      case "sparse" -> ascii(512, 8, 0);
      // escape-heavy: quotes and backslashes at ~25% of positions
      case "dense" -> ascii(128, 32, 0);
      // control characters, the six-character \\u00XX branch
      case "controls" -> ascii(128, 0, 16);
      default -> throw new IllegalArgumentException("Unknown shape: " + shape);
    };

    // Cross-check every candidate against the shipped implementation. With
    // failOnError (the plugin default) a disagreement is a hard failure, so a
    // faster-but-wrong candidate cannot post a score.
    check(current(), runs());
    check(current(), runsClean());
    check(current(), runsTable());
    check(current(), chars());
    check(current(), charsBranchless());
    check(current(), charsNoTable());
    check(current(), charsNoGuards());
  }

  private static void check(final String a, final String b) {
    if (!a.equals(b)) {
      throw new IllegalStateException("escape variants disagree:\n  " + a + "\n  " + b);
    }
  }

  /// Deterministic input: fixed seed, per the no-unseeded-randomness rule.
  /// `specials` quote/backslash positions and `controls` control-character
  /// positions are scattered through otherwise ordinary ASCII text.
  private static String ascii(final int len, final int specials, final int controls) {
    final var rnd = new Random(len * 31L + specials * 7L + controls);
    final var chars = new char[len];
    for (int i = 0; i < len; ++i) {
      chars[i] = (char) ('a' + rnd.nextInt(26));
    }
    for (int i = 0; i < specials; ++i) {
      chars[rnd.nextInt(len)] = (i & 1) == 0 ? '"' : '\\';
    }
    for (int i = 0; i < controls; ++i) {
      // 0x01..0x1F, mixing the short escapes (\n, \r, \t, \b, \f) with the
      // characters that have to take the \\u00XX branch
      chars[rnd.nextInt(len)] = (char) (1 + rnd.nextInt(0x1F));
    }
    return new String(chars);
  }

  /// Lowercase weighted 3x and spaces 12x to approximate prose-like JSON values;
  /// roughly 40% of draws land below the 0x5D table guard.
  private static final char[] MIXED_POOL = ("abcdefghijklmnopqrstuvwxyz".repeat(3)
      + " ".repeat(12)
      + "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
      + "0123456789"
      + ".,:-_/@").toCharArray();

  /// Every character <= 0x5B, so none of them short-circuits the guard.
  private static final char[] LOW_POOL =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 .,:-/@[".toCharArray();

  private static String fromPool(final int len, final char[] pool) {
    final var rnd = new Random(len * 31L + pool.length);
    final var chars = new char[len];
    for (int i = 0; i < len; ++i) {
      chars[i] = pool[rnd.nextInt(pool.length)];
    }
    return new String(chars);
  }

  private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

  /// True for exactly the characters that cannot appear raw in a JSON string
  /// literal: 0x00-0x1F, '"' (0x22), '\\' (0x5C). Sized to the largest of them,
  /// so the range check doubles as the membership test's first half.
  private static final boolean[] SPECIAL = new boolean['\\' + 1];

  static {
    for (int c = 0; c < 0x20; ++c) {
      SPECIAL[c] = true;
    }
    SPECIAL['"'] = true;
    SPECIAL['\\'] = true;
  }

  private static boolean special(final char c) {
    return c == '"' || c == '\\' || c < 0x20;
  }

  /// Non-short-circuit `|`: all three tests always evaluate, collapsing to one
  /// branch at the use site instead of three. The point is not only speed — it
  /// needs no static table, and a static-initializer table produces mutants that
  /// are unkillable by construction (AGENTS.md, `JHex$INIT_DIGITS`), so matching
  /// `SPECIAL` here buys the same scan without the permanent baseline rows.
  private static boolean specialBranchless(final char c) {
    return c < 0x20 | c == '"' | c == '\\';
  }

  @Benchmark
  public String current() {
    return JIUtil.escapeJson(input);
  }

  /// Shipped scan (do/while, vacuous `++from >= 0` tail) + bulk-run emit.
  /// Isolates the emit rewrite against `current`.
  @Benchmark
  public String runs() {
    final var str = input;
    final int len = str.length();
    int from = 0;
    char c;
    do {
      if (from == len) {
        return str;
      }
      c = str.charAt(from);
    } while (c != '"' && c != '\\' && c >= 0x20 && ++from >= 0);
    return emitRuns(str, len, from);
  }

  /// Same scan test, restructured as while/break so the dead `>= 0` comparison
  /// disappears. Isolates the loop restructure against `runs`.
  @Benchmark
  public String runsClean() {
    final var str = input;
    final int len = str.length();
    int from = 0;
    while (from < len && !special(str.charAt(from))) {
      ++from;
    }
    return from == len ? str : emitRuns(str, len, from);
  }

  /// Guarded lookup table in place of the three comparisons. Isolates the scan
  /// test against `runsClean`.
  @Benchmark
  public String runsTable() {
    final var str = input;
    final int len = str.length();
    int from = 0;
    while (from < len) {
      final char c = str.charAt(from);
      if (c < SPECIAL.length && SPECIAL[c]) {
        break;
      }
      ++from;
    }
    return from == len ? str : emitRunsTable(str, len, from);
  }

  private static String emitRuns(final String str, final int len, final int from) {
    final var escaped = new StringBuilder(len + 8 + (len >> 3)).append(str, 0, from);
    int run = from;
    for (int i = from; i < len; ++i) {
      final char c = str.charAt(i);
      if (special(c)) {
        if (i > run) {
          escaped.append(str, run, i);
        }
        appendEscape(escaped, c);
        run = i + 1;
      }
    }
    return len > run ? escaped.append(str, run, len).toString() : escaped.toString();
  }

  private static String emitRunsTable(final String str, final int len, final int from) {
    final var escaped = new StringBuilder(len + 8 + (len >> 3)).append(str, 0, from);
    int run = from;
    for (int i = from; i < len; ++i) {
      final char c = str.charAt(i);
      if (c < SPECIAL.length && SPECIAL[c]) {
        if (i > run) {
          escaped.append(str, run, i);
        }
        appendEscape(escaped, c);
        run = i + 1;
      }
    }
    return len > run ? escaped.append(str, run, len).toString() : escaped.toString();
  }

  private static void appendEscape(final StringBuilder escaped, final char c) {
    switch (c) {
      case '"' -> escaped.append('\\').append('"');
      case '\\' -> escaped.append('\\').append('\\');
      case '\n' -> escaped.append('\\').append('n');
      case '\r' -> escaped.append('\\').append('r');
      case '\t' -> escaped.append('\\').append('t');
      case '\b' -> escaped.append('\\').append('b');
      case '\f' -> escaped.append('\\').append('f');
      default -> escaped.append('\\').append('u').append('0').append('0')
          .append(HEX_DIGITS[c >> 4]).append(HEX_DIGITS[c & 0xF]);
    }
  }

  /// Same scan as `runsTable`; emits into a grown `char[]` and constructs the
  /// String once. Isolates the output buffer against `runsTable`.
  @Benchmark
  public String chars() {
    final var str = input;
    final int len = str.length();
    int from = 0;
    while (from < len) {
      final char c = str.charAt(from);
      if (c < SPECIAL.length && SPECIAL[c]) {
        break;
      }
      ++from;
    }
    if (from == len) {
      return str;
    }

    char[] out = new char[len + 8 + (len >> 3)];
    str.getChars(0, from, out, 0);
    int n = from;
    int run = from;
    for (int i = from; i < len; ++i) {
      final char c = str.charAt(i);
      if (c < SPECIAL.length && SPECIAL[c]) {
        final int span = i - run;
        // worst case for one escape is the six-character \\u00XX form
        out = ensure(out, n + span + 6);
        if (span > 0) {
          str.getChars(run, i, out, n);
          n += span;
        }
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
    if (tail > 0) {
      out = ensure(out, n + tail);
      str.getChars(run, len, out, n);
      n += tail;
    }
    return new String(out, 0, n);
  }

  /// `chars` with the branchless test in place of the lookup table. Isolates
  /// table-vs-branchless against `chars`; a tie means the table's static
  /// initializer is unnecessary cost in the mutation baseline.
  @Benchmark
  public String charsBranchless() {
    final var str = input;
    final int len = str.length();
    int from = 0;
    while (from < len && !specialBranchless(str.charAt(from))) {
      ++from;
    }
    if (from == len) {
      return str;
    }

    char[] out = new char[len + 8 + (len >> 3)];
    str.getChars(0, from, out, 0);
    int n = from;
    int run = from;
    for (int i = from; i < len; ++i) {
      final char c = str.charAt(i);
      if (specialBranchless(c)) {
        final int span = i - run;
        out = ensure(out, n + span + 6);
        if (span > 0) {
          str.getChars(run, i, out, n);
          n += span;
        }
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
    if (tail > 0) {
      out = ensure(out, n + tail);
      str.getChars(run, len, out, n);
      n += tail;
    }
    return new String(out, 0, n);
  }

  /// The cell every other `chars*` row leaves confounded: a `char[]` buffer with
  /// the **shipped three-comparison test**, no lookup table. Since the table is
  /// alphabet-sensitive (a mispredicted guard costs more than it saves on
  /// realistic text) and this test is not, this is the only `char[]` candidate
  /// whose emit win can be read on its own. Isolates the output buffer against
  /// `runsClean`, which differs from it only by StringBuilder-vs-`char[]`.
  @Benchmark
  public String charsNoTable() {
    final var str = input;
    final int len = str.length();
    int from = 0;
    while (from < len && !special(str.charAt(from))) {
      ++from;
    }
    if (from == len) {
      return str;
    }

    char[] out = new char[len + 8 + (len >> 3)];
    str.getChars(0, from, out, 0);
    int n = from;
    int run = from;
    for (int i = from; i < len; ++i) {
      final char c = str.charAt(i);
      if (special(c)) {
        final int span = i - run;
        out = ensure(out, n + span + 6);
        if (span > 0) {
          str.getChars(run, i, out, n);
          n += span;
        }
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
    if (tail > 0) {
      out = ensure(out, n + tail);
      str.getChars(run, len, out, n);
      n += tail;
    }
    return new String(out, 0, n);
  }

  /// `charsNoTable` with the `span > 0` and `tail > 0` guards deleted. A
  /// zero-length `getChars` is a no-op, so both guards are pure optimization —
  /// and each leaves two equivalent mutants in the `util` baseline (`>= 0` and
  /// always-true). Removing them trades those four rows for an unconditional
  /// no-op call; `dense`, where adjacent escapes make `span == 0` common, is the
  /// shape that says whether that trade costs anything.
  @Benchmark
  public String charsNoGuards() {
    final var str = input;
    final int len = str.length();
    int from = 0;
    while (from < len && !special(str.charAt(from))) {
      ++from;
    }
    if (from == len) {
      return str;
    }

    char[] out = new char[len + 8 + (len >> 3)];
    str.getChars(0, from, out, 0);
    int n = from;
    int run = from;
    for (int i = from; i < len; ++i) {
      final char c = str.charAt(i);
      if (special(c)) {
        final int span = i - run;
        out = ensure(out, n + span + 6);
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
    out = ensure(out, n + tail);
    str.getChars(run, len, out, n);
    n += tail;
    return new String(out, 0, n);
  }

  private static char[] ensure(final char[] out, final int needed) {
    if (needed <= out.length) {
      return out;
    }
    final var grown = new char[Math.max(needed, out.length << 1)];
    System.arraycopy(out, 0, grown, 0, out.length);
    return grown;
  }
}
