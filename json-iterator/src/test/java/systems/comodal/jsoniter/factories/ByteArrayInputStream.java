package systems.comodal.jsoniter.factories;

import systems.comodal.jsoniter.JsonIterator;

public final class ByteArrayInputStream implements JsonIteratorFactory {

  public static final JsonIteratorFactory INSTANCE = new ByteArrayInputStream();

  private ByteArrayInputStream() {
  }

  @Override
  public JsonIterator create(final String json) {
    return JsonIterator.parse(new java.io.ByteArrayInputStream(json.getBytes()));
  }

  @Override
  public JsonIterator create(final String json, final int charBufferLength) {
    return JsonIterator.parse(json.getBytes(), charBufferLength);
  }

  @Override
  public JsonIterator create(final String json, final int bufferLength, final int charBufferLength) {
    return JsonIterator.parse(json.getBytes(), charBufferLength);
  }

  @Override
  public String toString() {
    return "byte array input stream";
  }
}
