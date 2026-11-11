package pe.axiz.spring.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditPublishException;
import pe.axiz.core.commons.TransactionOutcome;
import pe.axiz.core.messaging.CloudEventJson;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.outbox.OutboxRecord;
import pe.axiz.core.outbox.OutboxStore;
import pe.axiz.core.outbox.OutboxWriter;

/**
 * Regression: audit evidence of a committed change must survive a crash after the commit. In
 * durable mode the event is written to the outbox inside the business transaction (same commit),
 * never kept only in memory.
 */
class DurableAuditTest {

  final List<OutboxRecord> outbox = new CopyOnWriteArrayList<>();
  final List<AuditEvent> fallback = new CopyOnWriteArrayList<>();

  OutboxStore store() {
    return (OutboxStore)
        Proxy.newProxyInstance(
            OutboxStore.class.getClassLoader(),
            new Class<?>[] {OutboxStore.class},
            (proxy, method, args) -> {
              if (method.getName().equals("append")) {
                outbox.add((OutboxRecord) args[0]);
                return null;
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }

  TransactionalAuditPublisher publisher(AuditOutsideTransaction mode) {
    return new TransactionalAuditPublisher(
        new OutboxWriter(store(), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)),
        "audit",
        fallback::add,
        mode);
  }

  static AuditEvent event(AuditEvent.Outcome outcome) {
    return new AuditEvent(
        "0190a1b2-0000-7000-8000-00000000aaaa",
        Instant.parse("2026-10-12T12:00:00Z"),
        "payments-business",
        "payment-order.create",
        "payment-order",
        "o-1",
        new AuditEvent.Actor("C-1001", "payments-web", null),
        outcome,
        null,
        "corr-1",
        "web",
        null,
        Map.of(),
        TransactionOutcome.UNKNOWN,
        TransactionOutcome.UNKNOWN);
  }

  @Test
  void aSuccessInsideTheTransactionIsWrittenToTheOutbox() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      publisher(AuditOutsideTransaction.ASYNC).publish(event(AuditEvent.Outcome.SUCCESS));
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    assertEquals(1, outbox.size());
    assertTrue(fallback.isEmpty(), "nothing kept only in memory");
    OutboxRecord r = outbox.get(0);
    assertEquals("audit", r.destination());
    EventEnvelope e = CloudEventJson.read(r.envelopeJson());
    assertEquals("0190a1b2-0000-7000-8000-00000000aaaa", e.id(), "event id for deduplication");
    assertEquals(AuditEvent.CLOUD_EVENT_TYPE, e.type());
    assertEquals("urn:axiz:payments-business", e.source());
    assertEquals("corr-1", e.extensions().get(EventEnvelope.CORRELATION_ID));
    assertTrue(new String(e.data(), StandardCharsets.UTF_8).contains("payment-order.create"));
  }

  @Test
  void failuresAndEventsOutsideTransactionsUseTheFallbackOrAreRefused() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      publisher(AuditOutsideTransaction.ASYNC).publish(event(AuditEvent.Outcome.FAILURE));
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    assertEquals(1, fallback.size(), "a failure would be rolled back with the transaction");
    publisher(AuditOutsideTransaction.ASYNC).publish(event(AuditEvent.Outcome.SUCCESS));
    assertEquals(2, fallback.size());
    assertThrows(
        AuditPublishException.class,
        () -> publisher(AuditOutsideTransaction.FAIL).publish(event(AuditEvent.Outcome.SUCCESS)));
    assertTrue(outbox.isEmpty());
  }
}
