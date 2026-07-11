package systems.comodal.jsoniter.jmh;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import systems.comodal.jsoniter.JIUtil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/// Compares the vectorized compress-store minifier (simdjson's json_minifier
/// design) against a scalar in-string-tracking baseline, on a whitespace-heavy
/// pretty-printed document (twitter.json, 617 KiB) and an already-compact one
/// (the Solana block response, ~4.7 MiB), where minification degenerates to a
/// copy.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class MinifyBench {

  private byte[] twitter;
  private byte[] block;

  @Setup
  public void setup() {
    try (final var in = MinifyBench.class.getResourceAsStream("/twitter.json")) {
      twitter = in.readAllBytes();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    try (final var in = new GZIPInputStream(MinifyBench.class.getResourceAsStream("/solana-block.json.gz"))) {
      block = in.readAllBytes();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    check(twitter);
    check(block);
  }

  private static void check(final byte[] doc) {
    final var vector = JIUtil.minify(doc);
    final var scalar = scalarMinify(doc);
    if (!Arrays.equals(vector, scalar)) {
      throw new IllegalStateException("minifiers disagree");
    }
  }

  @Benchmark
  public byte[] twitter_vector() {
    return JIUtil.minify(twitter);
  }

  @Benchmark
  public byte[] twitter_scalar() {
    return scalarMinify(twitter);
  }

  @Benchmark
  public byte[] block_vector() {
    return JIUtil.minify(block);
  }

  @Benchmark
  public byte[] block_scalar() {
    return scalarMinify(block);
  }

  private static byte[] scalarMinify(final byte[] src) {
    final byte[] out = new byte[src.length];
    boolean inString = false;
    int d = 0;
    for (int i = 0; i < src.length; ++i) {
      final byte c = src[i];
      if (inString) {
        out[d++] = c;
        if (c == '\\') {
          out[d++] = src[++i];
        } else if (c == '"') {
          inString = false;
        }
      } else if (c == '"') {
        inString = true;
        out[d++] = c;
      } else if (c != ' ' && c != '\n' && c != '\t' && c != '\r') {
        out[d++] = c;
      }
    }
    return Arrays.copyOf(out, d);
  }
}
