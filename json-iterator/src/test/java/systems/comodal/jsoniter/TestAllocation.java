package systems.comodal.jsoniter;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Asserts the allocation guarantees the sized readers exist for. PIT cannot
/// distinguish a grow-always or trim-always mutant behaviorally — the result
/// arrays are equal — so those properties are locked here through the
/// thread-allocation counter instead.
final class TestAllocation {

  private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();

  @BeforeAll
  static void requireAllocationCounters() {
    assumeTrue(THREADS.isThreadAllocatedMemorySupported());
    if (!THREADS.isThreadAllocatedMemoryEnabled()) {
      THREADS.setThreadAllocatedMemoryEnabled(true);
    }
  }

  /// Minimum allocation over repeated runs: the floor discards runs where
  /// JIT or GC machinery happened to allocate on this thread.
  private static long minAllocated(final Runnable op) {
    op.run(); // lazy-init and warmup outside the measurement
    long min = Long.MAX_VALUE;
    for (int i = 0; i < 32; ++i) {
      final long before = THREADS.getCurrentThreadAllocatedBytes();
      op.run();
      final long allocated = THREADS.getCurrentThreadAllocatedBytes() - before;
      if (allocated < min) {
        min = allocated;
      }
    }
    return min;
  }

  @Test
  void test_empty_and_null_array_reads_allocate_nothing() {
    final byte[] empty = "[]".getBytes();
    final byte[] nul = "null".getBytes();
    final var ji = JsonIterator.parse(empty);
    assertEquals(0, minAllocated(() -> ji.reset(empty).readLongArray(8)));
    assertEquals(0, minAllocated(() -> ji.reset(nul).readLongArray(8)));
    assertEquals(0, minAllocated(() -> ji.reset(empty).readIntArray(8)));
    assertEquals(0, minAllocated(() -> ji.reset(nul).readIntArray(8)));
    assertEquals(0, minAllocated(() -> ji.reset(empty).readByteArray(8)));
    assertEquals(0, minAllocated(() -> ji.reset(nul).readByteArray(8)));
  }

  @Test
  void test_exact_sized_array_reads_allocate_only_the_result() {
    final byte[] json = "[1,2,3]".getBytes();
    final var ji = JsonIterator.parse(json);
    // each bound admits exactly one array of the element type (header + data,
    // padded); any grow or trim copy at least doubles it
    final long longs = minAllocated(() -> ji.reset(json).readLongArray(3));
    assertTrue(longs > 0 && longs <= 64, "long[3] read allocated " + longs + " bytes");
    final long ints = minAllocated(() -> ji.reset(json).readIntArray(3));
    assertTrue(ints > 0 && ints <= 48, "int[3] read allocated " + ints + " bytes");
    final long bytes = minAllocated(() -> ji.reset(json).readByteArray(3));
    assertTrue(bytes > 0 && bytes <= 32, "byte[3] read allocated " + bytes + " bytes");
  }

  /// The reusable char buffer must grow *amortised* — double only when full —
  /// so decoding N chars costs O(log N) grows. Every "grow always" mutant on
  /// those guards is content-identical (the string still decodes correctly), so
  /// no assertEquals can see it; the only thing that changes is how much was
  /// allocated getting there. Starting from a 2-char buffer, correct decoding
  /// allocates a few hundred bytes, while a per-character double allocates
  /// 2^N — megabytes here. Without this bound those mutants either survive or,
  /// worse, are "killed" only by exhausting the heap in a long-string test,
  /// which races the PIT timeout and fails a loaded certification at random.
  @Test
  void test_char_buffer_grows_amortised_not_per_character() {
    // supplementary chars exercise the surrogate-split grows, the ascii tail the
    // general append grow; both live on the multi-byte decode path
    final var text = "\uD83D\uDE00".repeat(18) + "a".repeat(18);
    final byte[] doc = ('"' + text + '"').getBytes(java.nio.charset.StandardCharsets.UTF_8);
    // correctness first: the bound below is only meaningful if the read is right
    assertEquals(text, JsonIterator.parse(doc, 2).readString());
    final long allocated = minAllocated(() -> {
      final var read = JsonIterator.parse(doc, 2).readString();
      if (read.isEmpty()) {
        throw new AssertionError("unreachable; keeps the decode observable");
      }
    });
    assertTrue(
        allocated > 0 && allocated <= 8192,
        "decoding " + text.length() + " chars from a 2-char buffer allocated " + allocated
            + " bytes; amortised doubling costs a few hundred, per-character doubling megabytes");
  }

  @Test
  void test_guarded_primitive_reads_allocate_nothing() {
    final byte[] number = "42".getBytes();
    final byte[] string = "\"nope\"".getBytes();
    final var ji = JsonIterator.parse(number);
    assertEquals(0, minAllocated(() -> ji.reset(number).readLongOr(-1L)));
    assertEquals(0, minAllocated(() -> ji.reset(string).readLongOr(-1L)));
    assertEquals(0, minAllocated(() -> ji.reset(number).readIntOr(-1)));
    assertEquals(0, minAllocated(() -> ji.reset(string).readIntOr(-1)));
  }
}
