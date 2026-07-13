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

/// Tracks the lab's second open question: does the fork's hybrid
/// SWAR-prefix+vector string scan beat main's pure-SWAR scan, and from what
/// string length? The fork measured ~1.5x on long strings on NEON; short
/// strings dominate real JSON. Sweeps content length across a 256-string
/// document for both the widen (parse into charBuf) and skip
/// (skipPastEndQuote) shapes.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class StringScanKernelBench {

  /// 8/24 = typical field values, 44/88 = base58 keys and signatures,
  /// 512/4096 = log lines and blobs.
  @Param({"8", "24", "44", "88", "512", "4096"})
  private int strLen;

  private static final int STRINGS = 256;

  private byte[] doc;
  private int[] starts;
  private char[] out;

  @Setup
  public void setup() {
    final var value = new StringBuilder(strLen);
    for (int i = 0; i < strLen; ++i) {
      value.append((char) ('a' + (i % 26)));
    }
    final var sb = new StringBuilder((strLen + 3) * STRINGS + 2).append('[');
    for (int i = 0; i < STRINGS; ++i) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append('"').append(value).append('"');
    }
    doc = sb.append(']').toString().getBytes(StandardCharsets.US_ASCII);
    starts = new int[STRINGS];
    int pos = 1; // past '['
    for (int i = 0; i < STRINGS; ++i) {
      starts[i] = pos + 1; // past the opening quote
      pos += strLen + 3;   // quote + content + quote + comma
    }
    out = new char[strLen + 64];

    final var a = new char[strLen + 64];
    final var b = new char[strLen + 64];
    for (final int s : starts) {
      final int la = StringScanKernels.scalarWiden(doc, s, doc.length, a);
      final int lb = StringScanKernels.vectorWiden(doc, s, doc.length, b);
      if (la != lb || !Arrays.equals(a, 0, la, b, 0, lb)
          || StringScanKernels.scalarSkip(doc, s, doc.length) != StringScanKernels.vectorSkip(doc, s, doc.length)) {
        throw new IllegalStateException("kernels disagree at " + s);
      }
    }
  }

  @Benchmark
  public long widenScalar() {
    long sum = 0;
    for (final int s : starts) {
      sum += StringScanKernels.scalarWiden(doc, s, doc.length, out);
    }
    return sum;
  }

  @Benchmark
  public long widenVector() {
    long sum = 0;
    for (final int s : starts) {
      sum += StringScanKernels.vectorWiden(doc, s, doc.length, out);
    }
    return sum;
  }

  @Benchmark
  public long skipScalar() {
    long sum = 0;
    for (final int s : starts) {
      sum += StringScanKernels.scalarSkip(doc, s, doc.length);
    }
    return sum;
  }

  @Benchmark
  public long skipVector() {
    long sum = 0;
    for (final int s : starts) {
      sum += StringScanKernels.vectorSkip(doc, s, doc.length);
    }
    return sum;
  }
}
