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

  // Volatile stores make measured results escape; reading only a length, or
  // discarding a result, can let the JIT eliminate the allocation under test.
  private static volatile Object referenceSink;
  private static volatile long primitiveSink;

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
    try {
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
    } finally {
      // Do not retain one test's buffers across later tests or PIT mutants.
      referenceSink = null;
      primitiveSink = 0;
    }
  }

  @Test
  void test_empty_and_null_array_reads_allocate_nothing() {
    final byte[] empty = "[]".getBytes();
    final byte[] nul = "null".getBytes();
    final var ji = JsonIterator.parse(empty);
    assertEquals(0, minAllocated(() -> referenceSink = ji.reset(empty).readLongArray(8)));
    assertEquals(0, minAllocated(() -> referenceSink = ji.reset(nul).readLongArray(8)));
    assertEquals(0, minAllocated(() -> referenceSink = ji.reset(empty).readIntArray(8)));
    assertEquals(0, minAllocated(() -> referenceSink = ji.reset(nul).readIntArray(8)));
    assertEquals(0, minAllocated(() -> referenceSink = ji.reset(empty).readByteArray(8)));
    assertEquals(0, minAllocated(() -> referenceSink = ji.reset(nul).readByteArray(8)));
  }

  @Test
  void test_exact_sized_array_reads_allocate_only_the_result() {
    final byte[] json = "[1,2,3]".getBytes();
    final var ji = JsonIterator.parse(json);
    // each bound admits exactly one array of the element type (header + data,
    // padded); any grow or trim copy at least doubles it
    final long longs = minAllocated(() -> referenceSink = ji.reset(json).readLongArray(3));
    assertTrue(longs > 0 && longs <= 64, "long[3] read allocated " + longs + " bytes");
    final long ints = minAllocated(() -> referenceSink = ji.reset(json).readIntArray(3));
    assertTrue(ints > 0 && ints <= 48, "int[3] read allocated " + ints + " bytes");
    final long bytes = minAllocated(() -> referenceSink = ji.reset(json).readByteArray(3));
    assertTrue(bytes > 0 && bytes <= 32, "byte[3] read allocated " + bytes + " bytes");
  }

  /// Decoding from a small reusable buffer must complete within a bounded
  /// allocation budget. Correct doubling costs a few hundred bytes here.
  /// Growth is also capped by the document length: growing before the buffer
  /// fills can exhaust that cap and reject valid input, which this read must
  /// not do. The separate field-name test checks amortised growth over a
  /// sequence of reads.
  @Test
  void test_char_buffer_grows_amortised_not_per_character() {
    // supplementary chars exercise the surrogate-split grows, the ascii tail the
    // general append grow; both live on the multi-byte decode path
    final var text = "\uD83D\uDE00".repeat(18) + "a".repeat(18);
    final byte[] doc = ('"' + text + '"').getBytes(java.nio.charset.StandardCharsets.UTF_8);
    // correctness first: the bound below is only meaningful if the read is right
    assertEquals(text, JsonIterator.parse(doc, 2).readString());
    final long allocated = minAllocated(() -> referenceSink = JsonIterator.parse(doc, 2).readString());
    assertTrue(
        allocated > 0 && allocated <= 8192,
        "decoding " + text.length() + " chars from a 2-char buffer allocated " + allocated
            + " bytes; bounded doubling costs a few hundred");
  }

  /// Field names decode through the ascii fast path's
  /// `charBuf = new char[Math.max(len, charBuf.length << 1)]`. Halving that
  /// shift is content-safe — `Math.max` still admits `len`, so every name still
  /// decodes — but it drops the doubling headroom, so charBuf is resized to
  /// exactly the length each time and a run of lengthening names reallocates on
  /// every one. Invisible within a single read, which is why this walks one
  /// object whose names grow 64..192: amortised growth reallocates three times,
  /// exact sizing on all 129. testObject is the right vehicle because the
  /// FieldBufferFunction contract allocates nothing per field, so the buffer
  /// growth is the only thing the counter can see.
  @Test
  void test_field_name_widening_keeps_doubling_headroom() {
    final var doc = new StringBuilder("{");
    for (int i = 0; i < 129; ++i) {
      doc.append(i == 0 ? "" : ",").append('"').append("a".repeat(64 + i)).append("\":0");
    }
    final byte[] json = doc.append('}').toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    // correctness first: the bound below only means something if the names decode
    final int[] seen = {0};
    JsonIterator.parse(json).testObject((buf, offset, len, ji) -> {
      assertEquals(64 + seen[0]++, len);
      ji.skip();
      return true;
    });
    assertEquals(129, seen[0]);
    final long allocated = minAllocated(() -> {
      // fresh iterator per measurement, or charBuf is already at full size and
      // neither the correct nor the mutated sizing reallocates at all
      JsonIterator.parse(json, 8).testObject((buf, offset, len, ji) -> {
        // Publish every buffer, including those replaced by later growth.
        referenceSink = buf;
        ji.skip();
        return true;
      });
    });
    assertTrue(
        allocated > 0 && allocated <= 8192,
        "129 lengthening field names allocated " + allocated
            + " bytes; doubling headroom costs about 900, exact sizing about 33000");
  }

  /// `JIUtil.escapeJson` grows its output through
  /// `ensureCapacity`'s `new char[Math.max(needed, out.length << 1)]`, once per
  /// escape. Halving that shift is content-safe — `Math.max` still admits
  /// `needed`, so the escaped string is identical — but it sizes the buffer to
  /// exactly what this escape needs, so a densely-escaped input reallocates per
  /// escape instead of doubling. 512 control characters expand about six-fold:
  /// doubling gets there in three grows, exact sizing in 512.
  @Test
  void test_escape_growth_doubles_rather_than_sizing_to_each_escape() {
    final var dense = "\u0001".repeat(512);
    final var escaped = JIUtil.escapeJson(dense);
    // correctness first: the bound below only means something if the escape is right
    assertEquals(512 * 6, escaped.length());
    final long allocated = minAllocated(() -> referenceSink = JIUtil.escapeJson(dense));
    assertTrue(
        allocated > 0 && allocated <= 65_536,
        "escaping 512 control characters allocated " + allocated
            + " bytes; doubling costs tens of kilobytes, per-escape sizing megabytes");
  }

  @Test
  void test_guarded_primitive_reads_allocate_nothing() {
    final byte[] number = "42".getBytes();
    final byte[] string = "\"nope\"".getBytes();
    final var ji = JsonIterator.parse(number);
    assertEquals(0, minAllocated(() -> primitiveSink = ji.reset(number).readLongOr(-1L)));
    assertEquals(0, minAllocated(() -> primitiveSink = ji.reset(string).readLongOr(-1L)));
    assertEquals(0, minAllocated(() -> primitiveSink = ji.reset(number).readIntOr(-1)));
    assertEquals(0, minAllocated(() -> primitiveSink = ji.reset(string).readIntOr(-1)));
  }
}
