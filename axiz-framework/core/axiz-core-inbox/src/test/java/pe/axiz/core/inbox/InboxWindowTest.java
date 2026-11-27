package pe.axiz.core.inbox;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class InboxWindowTest {
  @Test
  void persistenceRequiresAPositiveLeaseStrictlyInsideRetention() {
    Instant now = Instant.parse("2026-11-18T12:00:00Z");
    Instant lease = now.plusSeconds(5);
    assertDoesNotThrow(() -> InboxStore.validateWindow(now, lease, lease.plusSeconds(1)));
    assertThrows(IllegalArgumentException.class, () -> InboxStore.validateWindow(now, now, lease));
    assertThrows(
        IllegalArgumentException.class,
        () -> InboxStore.validateWindow(now, now.minusSeconds(1), lease));
    assertThrows(
        IllegalArgumentException.class, () -> InboxStore.validateWindow(now, lease, lease));
    assertThrows(
        IllegalArgumentException.class,
        () -> InboxStore.validateWindow(now, lease, lease.minusSeconds(1)));
  }
}
