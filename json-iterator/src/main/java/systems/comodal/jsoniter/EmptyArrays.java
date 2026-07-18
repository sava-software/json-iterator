package systems.comodal.jsoniter;

/// Shared zero-length arrays returned for empty and null array reads.
final class EmptyArrays {

  static final byte[] BYTES = new byte[0];
  static final int[] INTS = new int[0];
  static final long[] LONGS = new long[0];

  private EmptyArrays() {
  }
}
