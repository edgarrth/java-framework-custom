package pe.axiz.core.audit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import pe.axiz.core.commons.TransactionOutcome;

@Timeout(10)
class AuditDeliveryModesTest {
  private AuditEvent event() {
    return new AuditEvent(
        "event",
        Instant.EPOCH,
        "service",
        "action",
        "resource",
        null,
        new AuditEvent.Actor(null, null, null),
        AuditEvent.Outcome.SUCCESS,
        null,
        null,
        null,
        null,
        Map.of());
  }

  @Test
  void synchronousDeliveryFailureNeverChangesBusinessCompletion() {
    var sync =
        new ConfirmedAuditPublisher(
            e -> {
              throw new IllegalStateException("transport");
            });
    assertDoesNotThrow(() -> sync.publish(event()));
    assertEquals(1, sync.unconfirmedCount());
    var nativeSync =
        new ConfirmedAsyncAuditPublisher(
            e -> {
              throw new IllegalStateException("submission");
            });
    assertDoesNotThrow(() -> nativeSync.publish(event()).toCompletableFuture().join());
    assertEquals(1, nativeSync.unconfirmedCount());
  }

  @Test
  void nativeSynchronousModeAwaitsTheActualAcknowledgementAndObservesItsFailure() {
    var transport = new CompletableFuture<Void>();
    var sync = new ConfirmedAsyncAuditPublisher(e -> transport);
    var delivered = sync.publish(event()).toCompletableFuture();
    assertFalse(delivered.isDone());
    transport.completeExceptionally(new IllegalStateException("acknowledgement"));
    assertDoesNotThrow(delivered::join);
    assertEquals(1, sync.unconfirmedCount());
  }

  @Test
  void asyncNativeEnqueueDoesNotWaitForTheTransportOrMiscountQueuedEvidenceAsLost()
      throws Exception {
    var entered = new CountDownLatch(1);
    var ack = new CompletableFuture<Void>();
    try (var publisher =
        new QueuedAsyncAuditPublisher(
            e -> {
              entered.countDown();
              return ack;
            },
            e -> {},
            1,
            Duration.ofSeconds(5))) {
      assertTrue(publisher.publish(event()).toCompletableFuture().isDone());
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      assertTrue(publisher.publish(event()).toCompletableFuture().isDone());
      assertEquals(1, publisher.queue().pending());
      assertEquals(0, publisher.queue().lostCount());
      ack.complete(null);
    }
  }

  @Test
  void nativeAcknowledgementTimeoutReachesFallbackAndCancelsTransport() throws Exception {
    var ack = new CompletableFuture<Void>();
    var fallback = new CountDownLatch(1);
    try (var publisher =
        new QueuedAsyncAuditPublisher(
            e -> ack, e -> fallback.countDown(), 1, Duration.ofMillis(20))) {
      publisher.publish(event());
      assertTrue(fallback.await(3, TimeUnit.SECONDS));
      assertTrue(ack.isCancelled());
      assertEquals(1, publisher.queue().failureCount());
      assertEquals(0, publisher.queue().lostCount());
    }
  }

  @Test
  void persistenceMarkerAndOutcomesPreserveTheOriginalEvidence() {
    var original = event().withOutcomes(TransactionOutcome.ROLLED_BACK, TransactionOutcome.UNKNOWN);
    var degraded = original.withPersistence("NOT_CONFIRMED");
    assertTrue(original.details().isEmpty());
    assertEquals("NOT_CONFIRMED", degraded.details().get("audit.persistence"));
    assertEquals(TransactionOutcome.ROLLED_BACK, degraded.transactionOutcome());
    assertEquals(TransactionOutcome.UNKNOWN, degraded.operationOutcome());
    assertThrows(UnsupportedOperationException.class, () -> degraded.details().put("x", "y"));
  }
}
