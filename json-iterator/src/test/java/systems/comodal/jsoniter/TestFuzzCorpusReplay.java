package systems.comodal.jsoniter;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Deterministically replays the committed json fuzz seed corpus through the
/// differential harness ([JsonFuzz]): byte- and char-sourced iterators must
/// produce identical event streams or both reject. This bridges the fuzz
/// corpus into the unit suite so PIT's mutants face the same oracle —
/// multibyte runs, escapes, and tricky numbers the from-scratch tests don't
/// assemble. New seeds (including promoted fuzz findings, per the
/// `regression-*` convention) replay here automatically.
final class TestFuzzCorpusReplay {

  @Test
  void test_json_seed_corpus_differential_replay() throws IOException, URISyntaxException {
    final var url = TestFuzzCorpusReplay.class.getResource("/fuzz/json");
    assumeTrue(url != null && "file".equals(url.getProtocol()), "seed corpus not on the classpath as a directory");
    final var dir = Path.of(url.toURI());
    try (final var files = Files.list(dir)) {
      final var seeds = files.filter(Files::isRegularFile).sorted().toList();
      assertFalse(seeds.isEmpty(), "empty seed corpus at " + dir);
      for (final var seed : seeds) {
        final byte[] data = Files.readAllBytes(seed);
        assertDoesNotThrow(() -> JsonFuzz.fuzzerTestOneInput(data), seed.getFileName().toString());
      }
    }
  }
}
