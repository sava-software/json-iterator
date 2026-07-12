package systems.comodal.jsoniter;

/// Context-passing variant of [FieldIndexPredicate].
@FunctionalInterface
public interface ContextFieldIndexPredicate<C> {

  boolean test(final C context, final int fieldIndex, final JsonIterator jsonIterator);
}
