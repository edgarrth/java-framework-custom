package pe.axiz.spring.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import pe.axiz.core.audit.AttemptOutcome;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditSealer;
import pe.axiz.core.commons.TransactionOutcome;

class AuditFailureEvidenceTest {
  @Test
  void independentPersistenceKeepsUnknownRollbackAndConfirmedOutcomesDistinct() {
    var original = DurableAuditTest.event(AuditEvent.Outcome.SUCCESS);
    var early = new DurableAuditAttempt(original);
    assertFalse(early.committed());
    var fallback = early.fallback(AuditTestTransactions.fallback());
    assertEquals("NOT_CONFIRMED", fallback.persistence());
    assertNull(fallback.integrity().previousAttempt());
    early.remember(original);
    assertEquals("NOT_CONFIRMED", early.fallback(AuditTestTransactions.fallback()).persistence());

    for (var outcome : TransactionOutcome.values()) {
      var attempt = new DurableAuditAttempt(original);
      var signed = AuditTestTransactions.durable().seal(original);
      assertEquals(signed, attempt.remember(signed));
      assertThrows(IllegalStateException.class, () -> attempt.remember(signed));
      attempt.complete(outcome);
      assertEquals(outcome == TransactionOutcome.COMMITTED, attempt.committed());
      var recovered = attempt.fallback(AuditTestTransactions.fallback());
      boolean rolledBack = outcome == TransactionOutcome.ROLLED_BACK;
      assertEquals(rolledBack ? "NOT_CONFIRMED" : "UNCERTAIN", recovered.persistence());
      assertEquals(
          rolledBack ? AttemptOutcome.ROLLED_BACK : AttemptOutcome.UNKNOWN,
          recovered.integrity().previousAttempt().outcome());
      assertEquals(signed.integrity().hash(), recovered.integrity().previousAttempt().hash());
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> new DurableAuditAttempt(AuditTestTransactions.durable().seal(original)));
  }

  @Test
  void cancelledOrInterruptedAttemptsCannotProceedToPersistence() {
    var original = DurableAuditTest.event(AuditEvent.Outcome.SUCCESS);
    var cancelled = new DurableAuditAttempt(original);
    cancelled.requireRunning();
    cancelled.cancel();
    assertThrows(CancellationException.class, cancelled::requireRunning);
    assertThrows(CancellationException.class, () -> cancelled.remember(original));
    try {
      Thread.currentThread().interrupt();
      assertThrows(
          CancellationException.class, () -> new DurableAuditAttempt(original).requireRunning());
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void invalidAuditBoundsAndUnresolvedHmacReferencesFailAtConfiguration() {
    for (String secret : new String[] {null, "", "${unresolved}"}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new AxizAuditProperties.Integrity(AuditSealer.Mode.HMAC, secret, "key-1"));
    }
    for (String id : new String[] {null, "", "key/id", "k".repeat(65)}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new AxizAuditProperties.Integrity(AuditSealer.Mode.HMAC, "audit/key", id));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> new AxizAuditProperties.Integrity(AuditSealer.Mode.HASH, null, "key"));
    for (Duration timeout : List.of(Duration.ZERO, Duration.ofMinutes(2)))
      assertThrows(IllegalArgumentException.class, () -> strict(timeout, 4, Duration.ofSeconds(1)));
    for (int concurrency : new int[] {0, 65})
      assertThrows(
          IllegalArgumentException.class,
          () -> strict(Duration.ofSeconds(1), concurrency, Duration.ofSeconds(1)));
    for (Duration checkpoint : List.of(Duration.ZERO, Duration.ofDays(2)))
      assertThrows(
          IllegalArgumentException.class, () -> strict(Duration.ofSeconds(1), 4, checkpoint));
    for (String destination : new String[] {null, "", "${destination}"})
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new AxizAuditProperties.Durable(
                  destination, AuditOutsideTransaction.FAIL, Duration.ofSeconds(1), 4));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AxizAuditProperties.Durable(
                "audit", AuditOutsideTransaction.FAIL, Duration.ZERO, 4));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AxizAuditProperties.Durable(
                "audit", AuditOutsideTransaction.FAIL, Duration.ofSeconds(1), 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AxizAuditProperties.Async(0, Duration.ZERO, Duration.ofSeconds(1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AxizAuditProperties.Async(1, Duration.ofMillis(-1), Duration.ofSeconds(1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AxizAuditProperties.Async(1, Duration.ZERO, Duration.ZERO));
  }

  private static AxizAuditProperties.Strict strict(
      Duration timeout, int concurrency, Duration checkpoint) {
    return new AxizAuditProperties.Strict(
        true, AxizAuditProperties.StrictMode.DURABLE, timeout, concurrency, checkpoint);
  }
}
