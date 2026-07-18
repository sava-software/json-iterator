package systems.comodal.jsoniter.factory;

import systems.comodal.jsoniter.JsonIterator;

import java.io.IOException;
import java.io.InputStream;

public interface JsonIterParser<T> {

  T parse(final JsonIterator ji) throws IOException;

  default T parse(final InputStream in) throws IOException {
    return parse(JsonIterator.parse(in));
  }

  /// @param bufSize ignored; the stream is always read fully.
  @Deprecated(forRemoval = true)
  default T parse(final InputStream in, final int bufSize) throws IOException {
    return parse(JsonIterator.parse(in));
  }

  default T parse(final byte[] json) throws IOException {
    return parse(JsonIterator.parse(json));
  }

  default T parse(final byte[] buf, final int head, final int tail) throws IOException {
    return parse(JsonIterator.parse(buf, head, tail));
  }

  default T parse(final String str) throws IOException {
    return parse(JsonIterator.parse(str));
  }
}
