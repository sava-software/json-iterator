package systems.comodal.jsoniter;

@FunctionalInterface
public interface CharBufferToDoubleFunction {

  double applyAsDouble(final char[] buf, final int offset, final int len);
}
