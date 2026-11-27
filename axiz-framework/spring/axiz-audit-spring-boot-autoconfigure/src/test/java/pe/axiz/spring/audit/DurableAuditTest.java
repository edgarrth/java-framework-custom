package pe.axiz.spring.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditPublishException;
import pe.axiz.core.commons.TransactionOutcome;
import pe.axiz.core.messaging.CloudEventJson;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.outbox.OutboxRecord;
import pe.axiz.core.outbox.OutboxStore;
import pe.axiz.core.outbox.OutboxWriter;

/**
 * La evidencia de auditoría de un cambio confirmado debe sobrevivir a una caída después del commit.
 * En modo durable, el evento se escribe en el outbox dentro de la transacción de negocio (mismo
 * commit) y nunca se mantiene solo en memoria.
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
              if (method.getName().equals("transactionResource"))
                return AuditTestTransactions.RESOURCE;
              if (method.getName().equals("append")) {
                outbox.add((OutboxRecord) args[0]);
                return null;
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }

  TransactionalAuditPublisher publisher(AuditOutsideTransaction mode) {
    return TransactionalAuditPublisher.builder()
        .outbox(new OutboxWriter(store(), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)))
        .destination("audit")
        .fallback(fallback::add)
        .outside(mode)
        .transactions(new AuditTestTransactions.Jdbc())
        .timeout(Duration.ofSeconds(2))
        .concurrency(1)
        .durable(AuditTestTransactions.durable())
        .degradation(AuditTestTransactions.fallback())
        .build();
  }

  static AuditEvent event(AuditEvent.Outcome outcome) {
    return AuditEvent.builder()
        .id("0190a1b2-0000-7000-8000-00000000aaaa")
        .occurredAt(Instant.parse("2026-10-12T12:00:00Z"))
        .service("payments-business")
        .action("payment-order.create")
        .resourceType("payment-order")
        .resourceId("o-1")
        .actor(new AuditEvent.Actor("C-1001", "payments-web", null))
        .outcome(outcome)
        .errorCode(null)
        .correlationId("corr-1")
        .channel("web")
        .tenant(null)
        .details(Map.of())
        .transactionOutcome(TransactionOutcome.UNKNOWN)
        .operationOutcome(TransactionOutcome.UNKNOWN)
        .persistence(null)
        .integrity(null)
        .detailsSanitized(0)
        .build();
  }

  @Test
  void aSuccessInsideTheTransactionIsWrittenToTheOutbox() {
    var publisher = publisher(AuditOutsideTransaction.ASYNC);
    new TransactionTemplate(new AuditTestTransactions.Jdbc())
        .executeWithoutResult(
            status -> {
              publisher.publish(event(AuditEvent.Outcome.SUCCESS));
              assertTrue(outbox.isEmpty(), "head and record are appended at beforeCommit");
            });
    assertEquals(1, outbox.size());
    assertTrue(fallback.isEmpty(), "nothing kept only in memory");
    OutboxRecord r = outbox.get(0);
    assertEquals("audit", r.destination());
    EventEnvelope e = CloudEventJson.read(r.envelopeJson());
    assertEquals(64, e.id().length(), "delivery id identifies the sealed attempt");
    assertEquals(AuditEvent.CLOUD_EVENT_TYPE, e.type());
    assertEquals("urn:axiz:payments-business", e.source());
    assertEquals("corr-1", e.extensions().get(EventEnvelope.CORRELATION_ID));
    assertTrue(new String(e.data(), StandardCharsets.UTF_8).contains("payment-order.create"));
  }

  @Test
  void failuresAndEventsOutsideTransactionsUseTheFallbackOrAreRefused() {
    publisher(AuditOutsideTransaction.ASYNC).publish(event(AuditEvent.Outcome.FAILURE));
    assertEquals(1, outbox.size(), "failure evidence commits in its independent transaction");
    assertTrue(fallback.isEmpty());
    publisher(AuditOutsideTransaction.ASYNC).publish(event(AuditEvent.Outcome.SUCCESS));
    assertEquals(1, fallback.size());
    assertThrows(
        AuditPublishException.class,
        () -> publisher(AuditOutsideTransaction.FAIL).publish(event(AuditEvent.Outcome.SUCCESS)));
    assertEquals(1, outbox.size());
  }
}
