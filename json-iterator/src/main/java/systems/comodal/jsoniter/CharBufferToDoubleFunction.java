package systems.comodal.jsoniter;

@FunctionalInterface
interface CharBufferToDoubleFunction {

  double applyAsDouble(final char[] buf, final int offset, final int len);
}
