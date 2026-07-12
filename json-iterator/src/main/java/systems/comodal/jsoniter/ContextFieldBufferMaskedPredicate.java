package systems.comodal.jsoniter;

/// @deprecated superseded by [ContextFieldIndexMaskedPredicate] via
/// [JsonIterator#testObject(Object, FieldMatcher, ContextFieldIndexMaskedPredicate)].
@Deprecated(forRemoval = true)
public interface ContextFieldBufferMaskedPredicate<C> {

  long BREAK_OUT = 0xffffffff_ffffffffL;

  long test(final C context, final long mask, final char[] buf, final int offset, final int len, final JsonIterator jsonIterator);
}

