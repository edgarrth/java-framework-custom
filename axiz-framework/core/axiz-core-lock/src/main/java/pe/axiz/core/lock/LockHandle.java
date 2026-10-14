package pe.axiz.core.lock;

import java.time.Duration;

/** An acquired lock. Close it (try-with-resources) to release. */
public interface LockHandle extends AutoCloseable {

  String resource();

  /** Unique token of this owner; only the owner can renew or release. */
  String token();

  /** Extends the lease; returns false when the lock was lost (expired and taken). */
  boolean renew(Duration lease);

  void release();

  @Override
  default void close() {
    release();
  }
}
