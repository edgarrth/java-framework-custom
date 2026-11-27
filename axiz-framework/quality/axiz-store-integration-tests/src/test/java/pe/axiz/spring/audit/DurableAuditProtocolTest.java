package pe.axiz.spring.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import pe.axiz.adapter.outbox.jdbc.Dialect;
import pe.axiz.adapter.outbox.jdbc.JdbcAuditChainState;
import pe.axiz.adapter.outbox.jdbc.JdbcOutboxStore;
import pe.axiz.core.audit.AttemptOutcome;
import pe.axiz.core.audit.AuditChainVerifier;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditEventJson;
import pe.axiz.core.audit.AuditSealer;
import pe.axiz.core.audit.InMemoryAuditChainState;
import pe.axiz.core.commons.TransactionOutcome;
import pe.axiz.core.messaging.CloudEventJson;
import pe.axiz.core.outbox.OutboxWriter;
import pe.axiz.spring.jdbc.SpringConnectionProvider;
import pe.axiz.spring.jdbc.SpringTransactionRunner;

/**
 * Las fallas alrededor de un commit real de base de datos no deben borrar ni clasificar mal los
 * intentos sellados.
 */
@Timeout(15)
class DurableAuditProtocolTest {
  private enum Fault {
    NONE,
    ROLLBACK,
    LOST_ACK,
    LATE_COMMIT,
    AFTER_COMMIT
  }

  private static final class Fixture implements AutoCloseable {
    final JdbcDataSource source = new JdbcDataSource();
    final AtomicReference<Fault> fault = new AtomicReference<>(Fault.NONE);
    final CountDownLatch committing = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final CountDownLatch committed = new CountDownLatch(1);
    final List<AuditEvent> fallback = new CopyOnWriteArrayList<>();
    final DataSourceTransactionManager transactions;
    final JdbcAuditChainState state;
    final TransactionalAuditPublisher publisher;

    Fixture(Duration timeout) {
      source.setURL("jdbc:h2:mem:protocol-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
      new ResourceDatabasePopulator(new ClassPathResource("axiz/sql/outbox-h2.sql"))
          .execute(source);
      transactions =
          new DataSourceTransactionManager(source) {
            @Override
            protected void doCommit(DefaultTransactionStatus status) {
              Fault selected = fault.getAndSet(Fault.NONE);
              if (selected == Fault.ROLLBACK) {
                super.doRollback(status);
                throw new UnexpectedRollbackException("rollback confirmed");
              }
              if (selected == Fault.LATE_COMMIT) {
                committing.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                boolean interrupted = false;
                while (release.getCount() != 0 && System.nanoTime() < deadline) {
                  try {
                    release.await(20, TimeUnit.MILLISECONDS);
                  } catch (InterruptedException ignored) {
                    interrupted = true;
                  }
                }
                super.doCommit(status);
                committed.countDown();
                if (interrupted) Thread.currentThread().interrupt();
                return;
              }
              super.doCommit(status);
              if (selected == Fault.LOST_ACK) throw new TransactionSystemException("ack lost");
            }
          };
      var connections = new SpringConnectionProvider(source);
      state =
          new JdbcAuditChainState(
              connections,
              source,
              "axiz_audit_chain",
              Dialect.H2,
              Clock.systemUTC(),
              Duration.ofSeconds(2));
      var store =
          new JdbcOutboxStore(
              connections, new SpringTransactionRunner(transactions), "axiz_outbox", Dialect.H2);
      var writer =
          new OutboxWriter(
              store,
              Clock.systemUTC(),
              (destination, event, write) -> {
                if (fault.get() == Fault.AFTER_COMMIT) {
                  TransactionSynchronizationManager.registerSynchronization(
                      new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                          throw new IllegalStateException("observer failed");
                        }
                      });
                }
                write.accept(event);
              });
      publisher =
          TransactionalAuditPublisher.builder()
              .outbox(writer)
              .destination("audit")
              .fallback(fallback::add)
              .outside(AuditOutsideTransaction.FAIL)
              .transactions(transactions)
              .timeout(timeout)
              .concurrency(2)
              .durable(new AuditSealer("durable", AuditSealer.Mode.HASH, state, null, null))
              .degradation(
                  new AuditSealer(
                      "degradation",
                      AuditSealer.Mode.HASH,
                      new InMemoryAuditChainState(Clock.systemUTC(), 8),
                      null,
                      null))
              .build();
    }

    List<AuditEvent> stored() {
      return new JdbcTemplate(source)
          .query(
              "SELECT envelope FROM axiz_outbox",
              (rows, index) ->
                  AuditEventJson.read(
                      new String(
                          CloudEventJson.read(rows.getString(1)).data(), StandardCharsets.UTF_8)));
    }

    @Override
    public void close() throws Exception {
      release.countDown();
      publisher.close();
    }
  }

  private static AuditEvent event(String id, AuditEvent.Outcome outcome) {
    return AuditEvent.builder()
        .id(id)
        .occurredAt(Instant.EPOCH)
        .service("service")
        .action("change")
        .resourceType("account")
        .resourceId("1")
        .actor(AuditEvent.Actor.system())
        .outcome(outcome)
        .errorCode(null)
        .correlationId(null)
        .channel(null)
        .tenant(null)
        .details(Map.of())
        .transactionOutcome(TransactionOutcome.ROLLED_BACK)
        .operationOutcome(TransactionOutcome.UNKNOWN)
        .persistence(null)
        .integrity(null)
        .detailsSanitized(0)
        .build();
  }

  @Test
  void rollbackReferenceIsNotConfusedWithAReusedSequence() throws Exception {
    try (var fixture = new Fixture(Duration.ofSeconds(2))) {
      fixture.fault.set(Fault.ROLLBACK);
      fixture.publisher.publish(event("failed", AuditEvent.Outcome.FAILURE));
      assertEquals(0, fixture.state.checkpoint("durable").sequence());
      var degraded = fixture.fallback.getFirst();
      assertEquals(AttemptOutcome.ROLLED_BACK, degraded.integrity().previousAttempt().outcome());
      assertEquals("NOT_CONFIRMED", degraded.persistence());
      fixture.publisher.publish(event("next", AuditEvent.Outcome.FAILURE));
      var next = fixture.stored().getFirst();
      assertEquals(degraded.integrity().previousAttempt().sequence(), next.integrity().sequence());
      assertNotEquals(degraded.integrity().previousAttempt().hash(), next.integrity().hash());
      assertEquals(
          AuditChainVerifier.IntegrityStatus.INTACT,
          AuditChainVerifier.verify(fixture.fallback, null, null).integrity());
    }
  }

  @Test
  void lostAcknowledgementKeepsBothAttemptsAndBothChains() throws Exception {
    try (var fixture = new Fixture(Duration.ofSeconds(2))) {
      fixture.fault.set(Fault.LOST_ACK);
      fixture.publisher.publish(event("same-fact", AuditEvent.Outcome.FAILURE));
      assertBothAttempts(fixture);
      fixture.fault.set(Fault.ROLLBACK);
      fixture.publisher.publish(event("later-fallback", AuditEvent.Outcome.FAILURE));
      assertEquals(2, fixture.fallback.size());
      assertEquals(
          AuditChainVerifier.IntegrityStatus.INTACT,
          AuditChainVerifier.verify(fixture.fallback, null, null).integrity());
    }
  }

  @Test
  void commitCanFinishAfterTheCallerDeadlineAndFallback() throws Exception {
    try (var fixture = new Fixture(Duration.ofMillis(250))) {
      fixture.fault.set(Fault.LATE_COMMIT);
      Thread caller =
          Thread.ofVirtual()
              .start(
                  () -> fixture.publisher.publish(event("same-fact", AuditEvent.Outcome.FAILURE)));
      assertTrue(fixture.committing.await(2, TimeUnit.SECONDS));
      caller.join(Duration.ofSeconds(2));
      assertEquals(1, fixture.fallback.size());
      assertTrue(fixture.stored().isEmpty());
      fixture.release.countDown();
      assertTrue(fixture.committed.await(2, TimeUnit.SECONDS));
      assertBothAttempts(fixture);
    }
  }

  private static void assertBothAttempts(Fixture fixture) {
    AuditEvent primary = fixture.stored().getFirst();
    AuditEvent secondary = fixture.fallback.getFirst();
    assertEquals(primary.id(), secondary.id());
    assertNotEquals(primary.deliveryId(), secondary.deliveryId());
    assertEquals(primary.integrity().hash(), secondary.integrity().previousAttempt().hash());
    assertEquals(AttemptOutcome.UNKNOWN, secondary.integrity().previousAttempt().outcome());
    assertEquals("UNCERTAIN", secondary.persistence());
    assertEquals(
        AuditChainVerifier.Completeness.COMPLETE_UP_TO,
        AuditChainVerifier.verify(fixture.stored(), null, fixture.state.checkpoint("durable"))
            .completeness());
    assertEquals(
        AuditChainVerifier.IntegrityStatus.INTACT,
        AuditChainVerifier.verify(fixture.fallback, null, null).integrity());
  }

  @Test
  void observerFailureAfterConfirmedCommitDoesNotInventAnotherAttempt() throws Exception {
    try (var fixture = new Fixture(Duration.ofSeconds(2))) {
      fixture.fault.set(Fault.AFTER_COMMIT);
      fixture.publisher.publish(event("committed", AuditEvent.Outcome.FAILURE));
      assertEquals(1, fixture.stored().size());
      assertTrue(fixture.fallback.isEmpty());
      assertEquals(0, fixture.publisher.notConfirmedCount());
    }
  }

  @Test
  void successIsSealedLateAndRollbackRestoresTheHead() throws Exception {
    try (var fixture = new Fixture(Duration.ofSeconds(2))) {
      var transaction = new TransactionTemplate(fixture.transactions);
      transaction.executeWithoutResult(
          status -> {
            fixture.publisher.publish(event("rolled-back", AuditEvent.Outcome.SUCCESS));
            assertEquals(0, fixture.state.checkpoint("durable").sequence());
            status.setRollbackOnly();
          });
      assertTrue(fixture.stored().isEmpty());
      transaction.executeWithoutResult(
          status -> fixture.publisher.publish(event("committed", AuditEvent.Outcome.SUCCESS)));
      var stored = fixture.stored().getFirst();
      assertEquals(1, stored.integrity().sequence());
      assertEquals(TransactionOutcome.COMMITTED, stored.transactionOutcome());
      assertNotNull(stored.integrity());
      fixture.publisher.close();
      assertThrows(
          IllegalStateException.class,
          () -> fixture.publisher.publish(event("late", AuditEvent.Outcome.SUCCESS)));
    }
  }
}
