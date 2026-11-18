package pe.axiz.core.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import pe.axiz.core.commons.TransactionOutcome;

class AuditSealDeliveryTest {
  private AuditEvent event() {
    return AuditEvent.builder()
        .id("event")
        .occurredAt(Instant.EPOCH)
        .service("service")
        .action("change")
        .resourceType("account")
        .resourceId("1")
        .actor(AuditEvent.Actor.system())
        .outcome(AuditEvent.Outcome.SUCCESS)
        .errorCode(null)
        .correlationId(null)
        .channel(null)
        .tenant(null)
        .details(Map.of("unsupported", new Object()))
        .transactionOutcome(TransactionOutcome.UNKNOWN)
        .operationOutcome(TransactionOutcome.COMMITTED)
        .persistence(null)
        .integrity(null)
        .detailsSanitized(0)
        .build();
  }

  private AuditSealer sealer() {
    return new AuditSealer(
        "delivery",
        AuditSealer.Mode.HASH,
        new InMemoryAuditChainState(Clock.systemUTC(), 8),
        null,
        null);
  }

  @Test
  void deliveryFailurePreservesTheSealAndAddsOnlyTransportMetadata() throws Exception {
    var attempted = new CompletableFuture<AuditEvent>();
    var delivered = new CompletableFuture<AuditEvent>();
    var queue =
        new AsyncAuditPublisher(
            event -> {
              attempted.complete(event);
              throw new IllegalStateException("destination failed");
            },
            delivered::complete,
            4,
            Duration.ZERO,
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            () -> () -> () -> {});
    try (var publisher = new SealingAuditPublisher(sealer(), queue)) {
      publisher.publish(event());
      var fallback = delivered.get(2, TimeUnit.SECONDS);
      assertEquals(attempted.get().integrity(), fallback.integrity());
      assertEquals("NOT_CONFIRMED", fallback.persistence());
      assertEquals(
          AuditChainVerifier.IntegrityStatus.INTACT,
          AuditChainVerifier.verify(List.of(fallback), null, null).integrity());
      assertEquals(1, publisher.detailsSanitizedCount());
      assertEquals(1L, publisher.chainSequences().get("delivery"));
    }
  }

  @Test
  void aSecondTransportFailureDoesNotDowngradeUncertainPersistence() throws Exception {
    var delivered = new CompletableFuture<AuditEvent>();
    try (var queue =
        new AsyncAuditPublisher(
            event -> {
              throw new IllegalStateException();
            },
            delivered::complete,
            4,
            Duration.ZERO,
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            () -> () -> () -> {})) {
      var signed = sealer().seal(event()).withPersistence("UNCERTAIN");
      queue.publish(signed);
      assertEquals("UNCERTAIN", delivered.get(2, TimeUnit.SECONDS).persistence());
    }
  }

  @Test
  void nativeDecoratorPreservesCancellationAndClosesItsTargetOnce() {
    var pending = new CompletableFuture<Void>();
    var closes = new AtomicInteger();
    class Destination implements AsyncAuditEventPublisher, AutoCloseable {
      @Override
      public CompletableFuture<Void> publish(AuditEvent event) {
        assertNotNull(event.integrity());
        return pending;
      }

      @Override
      public void close() {
        closes.incrementAndGet();
      }
    }
    var publisher = new SealingAsyncAuditPublisher(sealer(), new Destination());
    var result = publisher.publish(event()).toCompletableFuture();
    assertTrue(result.cancel(true));
    assertTrue(pending.isCancelled());
    publisher.close();
    publisher.close();
    assertEquals(1, closes.get());
    assertThrows(Exception.class, () -> publisher.publish(event()).toCompletableFuture().join());
  }
}
