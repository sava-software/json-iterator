package systems.comodal.jsoniter.jmh.vector;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/// Sizing bench, not a contest: establishes whether a custom Vector API
/// base64 decoder (Mula-style) could have headroom over the JDK's decoder,
/// whose decodeBlock is a HotSpot intrinsic on both aarch64 and x86. The
/// scalar table decoder approximates un-intrinsified performance; if the JDK
/// decoder is far ahead of it, the intrinsic is active and a custom kernel
/// has little to offer — record the verdict in VECTOR.md and move on.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class Base64DecodeBench {

  @Param({"1024", "16384", "262144"})
  private int payloadBytes;

  private byte[] encoded;
  private static final byte[] TABLE = new byte[128];

  static {
    Arrays.fill(TABLE, (byte) -1);
    final var alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    for (int i = 0; i < alphabet.length(); ++i) {
      TABLE[alphabet.charAt(i)] = (byte) i;
    }
  }

  @Setup
  public void setup() {
    final var payload = new byte[payloadBytes];
    for (int i = 0; i < payload.length; ++i) {
      payload[i] = (byte) (i * 131);
    }
    encoded = Base64.getEncoder().encode(payload);
    if (!Arrays.equals(Base64.getDecoder().decode(encoded), scalarTableDecode(encoded))) {
      throw new IllegalStateException("decoders disagree");
    }
  }

  @Benchmark
  public byte[] jdkDecoder() {
    return Base64.getDecoder().decode(encoded);
  }

  @Benchmark
  public byte[] scalarTable() {
    return scalarTableDecode(encoded);
  }

  private static byte[] scalarTableDecode(final byte[] src) {
    int len = src.length;
    while (len > 0 && src[len - 1] == '=') {
      --len;
    }
    final byte[] out = new byte[(len * 3) / 4];
    int o = 0;
    int i = 0;
    for (; i + 4 <= len; i += 4) {
      final int v = (TABLE[src[i]] << 18) | (TABLE[src[i + 1]] << 12) | (TABLE[src[i + 2]] << 6) | TABLE[src[i + 3]];
      out[o++] = (byte) (v >> 16);
      out[o++] = (byte) (v >> 8);
      out[o++] = (byte) v;
    }
    if (i < len) {
      final int rem = len - i;
      int v = (TABLE[src[i]] << 18) | (TABLE[src[i + 1]] << 12);
      if (rem == 3) {
        v |= TABLE[src[i + 2]] << 6;
        out[o++] = (byte) (v >> 16);
        out[o++] = (byte) (v >> 8);
      } else {
        out[o++] = (byte) (v >> 16);
      }
    }
    return out;
  }
}
