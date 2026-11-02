package pe.axiz.core.storage;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single subscription download. Chunks remain valid after delivery. Closing aborts the download,
 * including an unconsumed response, and is idempotent. Never aggregate an unbounded object in
 * memory.
 */
public final class AsyncStoredObject implements AutoCloseable {
  private final String key;
  private final ObjectMetadata metadata;
  private final Flow.Publisher<ByteBuffer> content;
  private final Runnable abort;
  private final AtomicBoolean closed = new AtomicBoolean();

  public AsyncStoredObject(
      String key, ObjectMetadata metadata, Flow.Publisher<ByteBuffer> content, Runnable abort) {
    this.key = ObjectKey.validate(key);
    this.metadata = Objects.requireNonNull(metadata);
    this.content = Objects.requireNonNull(content);
    this.abort = Objects.requireNonNull(abort);
  }

  public String key() {
    return key;
  }

  public ObjectMetadata metadata() {
    return metadata;
  }

  public Flow.Publisher<ByteBuffer> content() {
    return content;
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) abort.run();
  }
}
