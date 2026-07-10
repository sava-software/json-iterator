package systems.comodal.jsoniter.factories;

import systems.comodal.jsoniter.IndexedJsonIterator;
import systems.comodal.jsoniter.JsonIterator;

public final class IndexedCharArray implements JsonIteratorFactory {

  public static final JsonIteratorFactory INSTANCE = new IndexedCharArray();

  private IndexedCharArray() {
  }

  @Override
  public JsonIterator create(final String json) {
    return IndexedJsonIterator.parse(json.toCharArray());
  }

  @Override
  public JsonIterator create(final String json, final int charBufferLength) {
    return IndexedJsonIterator.parse(json.toCharArray());
  }

  @Override
  public JsonIterator create(final String json, final int bufferLength, final int charBufferLength) {
    return IndexedJsonIterator.parse(json.toCharArray());
  }

  @Override
  public String toString() {
    return "indexed char array";
  }
}
