package systems.comodal.jsoniter.jmh.vector;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/// Lab reference for the simdjson-style stage-1 structural indexer over the
/// 4.7 MiB Solana block: raw indexing throughput (known to be capped ~2 GB/s
/// on NEON by mask.toLong extraction) and the incremental cost of inline
/// UTF-8 validation. First thing to re-measure on 256/512-bit lanes.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class Stage1KernelBench {

  private byte[] json;
  private StructuralIndex index;

  @Setup
  public void setup() {
    try (final var in = new GZIPInputStream(Stage1KernelBench.class.getResourceAsStream("/solana-block.json.gz"))) {
      json = in.readAllBytes();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    index = new StructuralIndex();
    if (stage1() != stage1Validating() || stage1() != stage1Scalar()) {
      throw new IllegalStateException("stage 1 variants disagree on the structural count");
    }
  }

  @Benchmark
  public int stage1() {
    index.index(json, 0, json.length);
    return index.count();
  }

  @Benchmark
  public int stage1Scalar() {
    index.indexScalar(json, 0, json.length);
    return index.count();
  }

  @Benchmark
  public int stage1Validating() {
    final var utf8 = new Utf8Validator();
    index.index(json, 0, json.length, utf8);
    utf8.finish();
    return index.count();
  }
}
