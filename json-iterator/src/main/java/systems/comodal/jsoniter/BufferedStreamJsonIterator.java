package systems.comodal.jsoniter;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Base64;

final class BufferedStreamJsonIterator extends BytesJsonIterator {

  private InputStream in;

  BufferedStreamJsonIterator(final InputStream in, final byte[] buf, final int head, final int tail) {
    super(buf, head, tail);
    this.in = in;
  }

  BufferedStreamJsonIterator(final InputStream in, final byte[] buf, final int head, final int tail, final int charBufferLength) {
    super(buf, head, tail, charBufferLength);
    this.in = in;
  }

  @Override
  public void close() throws IOException {
    in.close();
  }

  @Override
  public boolean supportsMarkReset() {
    return false;
  }

  @Override
  public int mark() {
    throw new UnsupportedOperationException("Mark is not supported when using an InputStream.");
  }

  @Override
  public JsonIterator reset(final int mark) {
    throw new UnsupportedOperationException("Reset via mark is not supported when using an InputStream.");
  }

  @Override
  public JsonIterator reset(final byte[] buf) {
    return new BytesJsonIterator(buf, 0, buf.length);
  }

  @Override
  public JsonIterator reset(final byte[] buf, final int head, final int tail) {
    return new BytesJsonIterator(buf, head, tail);
  }

  @Override
  public JsonIterator reset(final InputStream in) {
    this.in = in;
    this.head = 0;
    this.tail = 0;
    return this;
  }

  @Override
  public JsonIterator reset(final InputStream in, final int bufSize) {
    if (buf.length != bufSize) {
      this.buf = new byte[bufSize];
    }
    this.in = in;
    this.head = 0;
    this.tail = 0;
    return this;
  }

  @Override
  boolean loadMore() {
    try {
      final int n = in.read(buf);
      if (n < 1) {
        if (n == -1) {
          return false;
        } else {
          throw reportError("loadMore", "read from input stream returned " + n);
        }
      } else {
        head = 0;
        tail = n;
        return true;
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  byte read() {
    if (head == tail && !loadMore()) {
      throw reportError("read", "no more to read");
    } else {
      return buf[head++];
    }
  }

  @Override
  public byte[] decodeBase64String() {
    final var base64 = readString();
    return base64 == null ? null : Base64.getDecoder().decode(base64);
  }
}
