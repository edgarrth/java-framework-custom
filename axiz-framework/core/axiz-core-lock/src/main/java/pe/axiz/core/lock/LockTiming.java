package pe.axiz.core.lock;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Monotonic lease deadline shared by imperative and reactive orchestration, including drift margin.
 */
public final class LockTiming {
  private LockTiming() {}

  public static long validUntil(long sent, Duration lease) {
    long nanos = lease.toNanos();
    return sent + nanos - (nanos / 100 + TimeUnit.MILLISECONDS.toNanos(2));
  }
}
