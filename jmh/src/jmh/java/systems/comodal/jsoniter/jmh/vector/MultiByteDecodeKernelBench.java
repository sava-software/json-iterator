package systems.comodal.jsoniter.jmh.vector;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/// Tracks the lab's third question across the breadth of JSON string content
/// — the decode path serves arbitrary documents, not one consumer's shape.
/// Profiles sweep the axis that decides the contest: the length of clean
/// ascii runs between stops (escapes / multibyte lead bytes). Dense-stop
/// content (CJK) is the vector variant's worst case by construction.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class MultiByteDecodeKernelBench {

  /// ascii_newlines: log lines, an escape every ~40 chars.
  /// european: latin text, a two-byte accent every ~12 chars.
  /// cjk: dense three-byte characters, stops everywhere.
  /// escaped_json: JSON embedded in strings, escapes every few chars.
  /// emoji_mixed: ascii with four-byte emoji clusters every ~20 chars.
  @Param({"ascii_newlines", "european", "cjk", "escaped_json", "emoji_mixed"})
  private String profile;

  private static final int STRINGS = 128;
  private static final int TARGET_CHARS = 240;

  private byte[] doc;
  private int[] starts;
  private char[] out;

  @Setup
  public void setup() {
    final var value = new StringBuilder(TARGET_CHARS + 8);
    switch (profile) {
      case "ascii_newlines" -> {
        while (value.length() < TARGET_CHARS) {
          value.append("Program log: instruction consumed 4213 compute units\\n");
        }
      }
      case "european" -> {
        while (value.length() < TARGET_CHARS) {
          value.append("Ce parseur gère les caractères accentués: é à ü ñ ø. ");
        }
      }
      case "cjk" -> {
        while (value.length() < TARGET_CHARS) {
          value.append("高性能向量化解析器基准测试用例，中文密集内容。");
        }
      }
      case "escaped_json" -> {
        while (value.length() < TARGET_CHARS) {
          value.append("{\\\"k\\\":\\\"v\\\",\\\"n\\\":[1,2]} ");
        }
      }
      case "emoji_mixed" -> {
        while (value.length() < TARGET_CHARS) {
          value.append("status update complete 🚀 latency nominal 📊 ");
        }
      }
      default -> throw new IllegalStateException(profile);
    }
    final var one = value.toString();
    final var sb = new StringBuilder().append('[');
    for (int i = 0; i < STRINGS; ++i) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append('"').append(one).append('"');
    }
    doc = sb.append(']').toString().getBytes(StandardCharsets.UTF_8);
    starts = new int[STRINGS];
    final byte[] oneBytes = one.getBytes(StandardCharsets.UTF_8);
    int pos = 1;
    for (int i = 0; i < STRINGS; ++i) {
      starts[i] = pos + 1;
      pos += oneBytes.length + 3;
    }
    out = new char[TARGET_CHARS * 2 + 64];

    final var a = new char[out.length];
    final var b = new char[out.length];
    for (final int s : starts) {
      final int la = MultiByteDecodeKernels.decodeScalar(doc, s, doc.length, a, 0);
      final int lb = MultiByteDecodeKernels.decodeVector(doc, s, doc.length, b, 0);
      if (la != lb || !Arrays.equals(a, 0, la, b, 0, lb)) {
        throw new IllegalStateException("decoders disagree at " + s);
      }
    }
  }

  @Benchmark
  public long decodeScalar() {
    long sum = 0;
    for (final int s : starts) {
      sum += MultiByteDecodeKernels.decodeScalar(doc, s, doc.length, out, 0);
    }
    return sum;
  }

  @Benchmark
  public long decodeVector() {
    long sum = 0;
    for (final int s : starts) {
      sum += MultiByteDecodeKernels.decodeVector(doc, s, doc.length, out, 0);
    }
    return sum;
  }
}
