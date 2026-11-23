package pe.axiz.spring.idempotency;

import java.util.Arrays;
import java.util.Objects;

/**
 * Copia propia y acotada de la respuesta; los búferes descartados se borran al crecer, al
 * desbordarse y al completar.
 */
final class BoundedCapture implements AutoCloseable {
  private final int limit;
  private byte[] bytes;
  private int size;
  private boolean overflowed;

  BoundedCapture(int limit) {
    if (limit < 0) throw new IllegalArgumentException("Negative capture limit");
    this.limit = limit;
    bytes = new byte[Math.min(limit, 64)];
  }

  synchronized void write(byte[] chunk, int offset, int length) {
    Objects.checkFromIndexSize(offset, length, chunk.length);
    if (overflowed) return;
    if ((long) size + length > limit) {
      markOverflowed();
      return;
    }
    int needed = size + length;
    if (needed > bytes.length) {
      int capacity = (int) Math.min(limit, Math.max(needed, (long) bytes.length * 2));
      byte[] grown = Arrays.copyOf(bytes, capacity);
      Arrays.fill(bytes, (byte) 0);
      bytes = grown;
    }
    System.arraycopy(chunk, offset, bytes, size, length);
    size = needed;
  }

  void write(byte[] chunk) {
    write(chunk, 0, chunk.length);
  }

  synchronized void markOverflowed() {
    overflowed = true;
    Arrays.fill(bytes, (byte) 0);
    size = 0;
  }

  synchronized boolean overflowed() {
    return overflowed;
  }

  synchronized byte[] bytes() {
    return Arrays.copyOf(bytes, size);
  }

  synchronized int buffered() {
    return size;
  }

  @Override
  public synchronized void close() {
    markOverflowed();
  }
}
