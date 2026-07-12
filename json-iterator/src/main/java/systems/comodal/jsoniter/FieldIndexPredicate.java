package systems.comodal.jsoniter;

/// Receives each object field name resolved to its index in a
/// [FieldMatcher]'s declared order, or -1 for a name the matcher does not
/// know. Return false to break out of the enclosing object.
@FunctionalInterface
public interface FieldIndexPredicate {

  boolean test(final int fieldIndex, final JsonIterator jsonIterator);
}
