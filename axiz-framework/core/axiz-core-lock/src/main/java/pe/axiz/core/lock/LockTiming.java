package pe.axiz.core.lock;

import java.time.Duration;
import pe.axiz.core.commons.LeaseTiming;

/**
 * Monotonic lease deadline shared by imperative and reactive orchestration, including drift margin.
 */
public final class LockTiming {
  private LockTiming() {}

  public static long validUntil(long sent, Duration lease) {
    return LeaseTiming.validUntil(sent, lease);
  }
}
