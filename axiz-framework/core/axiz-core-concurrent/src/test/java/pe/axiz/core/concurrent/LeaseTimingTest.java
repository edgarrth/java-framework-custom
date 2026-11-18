package pe.axiz.core.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class LeaseTimingTest {
  @Test
  void deadlineIncludesTheSharedDriftMarginAndSurvivesNanoTimeWraparound() {
    Duration lease = Duration.ofMinutes(3);
    assertEquals(Duration.ofMillis(1802), LeaseTiming.margin(lease));
    long start = Long.MAX_VALUE - 100;
    assertEquals(lease.minusMillis(1802).toNanos(), LeaseTiming.validUntil(start, lease) - start);
    assertEquals(10 + 97_000_000L, LeaseTiming.validUntil(10, Duration.ofMillis(100)));
  }

  @Test
  void invalidLeasesFailBeforeComputingADeadline() {
    assertThrows(IllegalArgumentException.class, () -> LeaseTiming.margin(Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class, () -> LeaseTiming.validUntil(0, Duration.ofSeconds(-1)));
  }
}
