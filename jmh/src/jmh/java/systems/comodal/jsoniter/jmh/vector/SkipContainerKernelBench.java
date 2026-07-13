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
import java.util.concurrent.TimeUnit;

/// Instrument for the standing skipContainer question (the fork's integrated
/// vector walk lost skip-heavy workloads on NEON; first candidate for the
/// wide-lane verdict). Profiles sweep structural density — the axis that
/// decides dense-hit vs sparse-hit economics:
/// stringHeavy = solana-meta shape (long base58 strings, sparse structurals);
/// numericDense = balance arrays (a structural every few bytes);
/// nested = deep small objects (dense braces).
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class SkipContainerKernelBench {

  @Param({"stringHeavy", "numericDense", "nested"})
  private String profile;

  private byte[] doc;
  private int start;
  private byte open;
  private byte close;

  @Setup
  public void setup() {
    final var sb = new StringBuilder(1 << 16);
    switch (profile) {
      case "stringHeavy" -> {
        sb.append('{');
        for (int i = 0; i < 200; ++i) {
          if (i > 0) {
            sb.append(',');
          }
          sb.append("\"k").append(i).append("\":\"")
              .append("5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d6cgnKsW3uYrx".repeat(1 + (i % 2)))
              .append('"');
        }
        sb.append('}');
        open = '{';
        close = '}';
      }
      case "numericDense" -> {
        sb.append('[');
        for (int i = 0; i < 4000; ++i) {
          if (i > 0) {
            sb.append(',');
          }
          sb.append(1000000 + i * 31);
        }
        sb.append(']');
        open = '[';
        close = ']';
      }
      case "nested" -> {
        sb.append('{');
        for (int i = 0; i < 400; ++i) {
          if (i > 0) {
            sb.append(',');
          }
          sb.append("\"f").append(i).append("\":{\"a\":1,\"b\":[2,3],\"c\":{\"d\":4}}");
        }
        sb.append('}');
        open = '{';
        close = '}';
      }
      default -> throw new IllegalStateException(profile);
    }
    doc = sb.toString().getBytes(StandardCharsets.US_ASCII);
    start = 1; // just inside the opening bracket

    final int a = SkipContainerKernels.scalarSkipContainer(doc, start, doc.length, open, close);
    final int b = SkipContainerKernels.vectorSkipContainer(doc, start, doc.length, open, close);
    if (a != b || a != doc.length) {
      throw new IllegalStateException("kernels disagree: " + a + " vs " + b + " vs " + doc.length);
    }
  }

  @Benchmark
  public int scalar() {
    return SkipContainerKernels.scalarSkipContainer(doc, start, doc.length, open, close);
  }

  @Benchmark
  public int vector() {
    return SkipContainerKernels.vectorSkipContainer(doc, start, doc.length, open, close);
  }
}
