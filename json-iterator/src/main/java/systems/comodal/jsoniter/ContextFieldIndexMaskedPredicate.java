package systems.comodal.jsoniter;

/// Masked variant of [ContextFieldIndexPredicate]: threads a caller-managed
/// bitmask through the object walk so a parser can record which fields it
/// has seen (`mask | (1L << fieldIndex)`) and return [#BREAK_OUT] once the
/// set is complete, skipping the rest of the object.
@FunctionalInterface
public interface ContextFieldIndexMaskedPredicate<C> {

  long BREAK_OUT = 0xffffffff_ffffffffL;

  long test(final C context, final long mask, final int fieldIndex, final JsonIterator jsonIterator);
}
