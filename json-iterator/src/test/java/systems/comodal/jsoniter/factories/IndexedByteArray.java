package systems.comodal.jsoniter.factories;

import systems.comodal.jsoniter.IndexedJsonIterator;
import systems.comodal.jsoniter.JsonIterator;

public final class IndexedByteArray implements JsonIteratorFactory {

  public static final JsonIteratorFactory INSTANCE = new IndexedByteArray();

  private IndexedByteArray() {
  }

  @Override
  public JsonIterator create(final String json) {
    return IndexedJsonIterator.parse(json);
  }

  @Override
  public JsonIterator create(final String json, final int charBufferLength) {
    return IndexedJsonIterator.parse(json);
  }

  @Override
  public JsonIterator create(final String json, final int bufferLength, final int charBufferLength) {
    return IndexedJsonIterator.parse(json);
  }

  @Override
  public String toString() {
    return "indexed byte array";
  }
}
