package pe.axiz.spring.idempotency;

import java.io.ByteArrayOutputStream;

/**
 * Copy of a response body for storage that stops buffering once {@code limit} is exceeded, so a
 * large response never grows memory: it is still sent to the client, only its status and headers
 * are stored.
 */
final class BoundedCapture {

  private final int limit;
  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private boolean overflowed;

  BoundedCapture(int limit) {
    this.limit = limit;
  }

  synchronized void write(byte[] chunk, int offset, int length) {
    if (overflowed) {
      return;
    }
    if ((long) out.size() + length > limit) {
      overflowed = true;
      out.reset();
      return;
    }
    out.write(chunk, offset, length);
  }

  void write(byte[] chunk) {
    write(chunk, 0, chunk.length);
  }

  /** Gives up the body copy (streamed responses). */
  synchronized void markOverflowed() {
    overflowed = true;
    out.reset();
  }

  synchronized boolean overflowed() {
    return overflowed;
  }

  synchronized byte[] bytes() {
    return out.toByteArray();
  }

  /** Bytes buffered so far (never above the limit). */
  synchronized int buffered() {
    return out.size();
  }
}
