package systems.comodal.jsoniter.jmh.vector;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/// Lab referee for the minify kernel: the vectorized stage-1-mask minifier
/// (a ~1.9x win over the published scalar library path on NEON when last
/// measured on feature/vectorize) against a plain scalar state machine. The
/// scalar twin here is also the reference shape for any library promotion.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class MinifyKernelBench {

  @Param({"twitter", "solana"})
  private String document;

  private byte[] json;

  @Setup
  public void setup() {
    try {
      if (document.equals("solana")) {
        try (final var in = new GZIPInputStream(MinifyKernelBench.class.getResourceAsStream("/solana-block.json.gz"))) {
          json = in.readAllBytes();
        }
      } else {
        try (final var in = MinifyKernelBench.class.getResourceAsStream("/twitter.json")) {
          json = in.readAllBytes();
        }
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    if (!Arrays.equals(vector(), scalar())) {
      throw new IllegalStateException("minify variants disagree");
    }
  }

  @Benchmark
  public byte[] vector() {
    return JsonMinifier.minify(json, 0, json.length);
  }

  @Benchmark
  public byte[] scalar() {
    return scalarMinify(json, 0, json.length);
  }

  static byte[] scalarMinify(final byte[] json, final int offset, final int len) {
    final byte[] out = new byte[len];
    int o = 0;
    boolean inString = false;
    for (int i = offset, to = offset + len; i < to; ++i) {
      final byte b = json[i];
      if (inString) {
        out[o++] = b;
        if (b == '\\') {
          if (++i < to) {
            out[o++] = json[i];
          }
        } else if (b == '"') {
          inString = false;
        }
      } else if (b == '"') {
        inString = true;
        out[o++] = b;
      } else if (b != ' ' && b != '\t' && b != '\n' && b != '\r') {
        out[o++] = b;
      }
    }
    return Arrays.copyOf(out, o);
  }
}
