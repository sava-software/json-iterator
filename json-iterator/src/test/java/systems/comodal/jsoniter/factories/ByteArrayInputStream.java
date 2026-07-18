package systems.comodal.jsoniter.factories;

import systems.comodal.jsoniter.JsonIterator;

import java.io.IOException;
import java.io.UncheckedIOException;

public final class ByteArrayInputStream implements JsonIteratorFactory {

  public static final JsonIteratorFactory INSTANCE = new ByteArrayInputStream();

  private ByteArrayInputStream() {
  }

  /// The stream API reads fully and iterates the resulting `byte[]`; sizing
  /// the char buffer requires routing the fully-read bytes explicitly.
  private static JsonIterator create(final java.io.ByteArrayInputStream in, final int charBufferLength) {
    try (in) {
      return JsonIterator.parse(in.readAllBytes(), charBufferLength);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public JsonIterator create(final String json) {
    return JsonIterator.parse(new java.io.ByteArrayInputStream(json.getBytes()));
  }

  @Override
  public JsonIterator create(final String json, final int charBufferLength) {
    return create(new java.io.ByteArrayInputStream(json.getBytes()), charBufferLength);
  }

  @Override
  public JsonIterator create(final String json, final int bufferLength, final int charBufferLength) {
    // bufferLength was always ignored: streams are read fully upfront
    return create(new java.io.ByteArrayInputStream(json.getBytes()), charBufferLength);
  }

  @Override
  public String toString() {
    return "byte array input stream";
  }
}
