package pe.axiz.spring.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.reactive.TransactionContextManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import pe.axiz.core.commons.tx.TransactionRunner;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.context.RequestMetadata;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventFactory;
import pe.axiz.core.messaging.EventPublisher;
import pe.axiz.core.messaging.PayloadCodec;
import pe.axiz.core.outbox.OutboxRelayListener;
import pe.axiz.core.outbox.OutboxStore;
import pe.axiz.core.outbox.OutboxWriter;
import pe.axiz.kit.reactor.outbox.ReactiveOutboxWriter;
import reactor.core.publisher.Mono;

/**
 * Composición y admisión de transacciones; las pruebas de integración del proveedor verifican la
 * atomicidad de la base de datos.
 */
class OutboxCompositionTest {
  private static final Clock CLOCK = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
  private static final RequestMetadata METADATA =
      RequestMetadata.builder("correlation-123", "request-123").tenantId("tenant-a").build();

  private static EventFactory factory() {
    return new EventFactory(
        "payments",
        "payment",
        new PayloadCodec() {
          public String contentType() {
            return "application/json";
          }

          public byte[] encode(Object payload) {
            return payload.toString().getBytes(StandardCharsets.UTF_8);
          }

          public <T> T decode(byte[] bytes, Class<T> type) {
            throw new UnsupportedOperationException();
          }
        },
        () -> "generated-event",
        CLOCK);
  }

  @Test
  void imperativeWritesRequireATransactionAndPreserveCallerContext() {
    var store = mock(OutboxStore.class);
    var events = new TransactionalEvents(factory(), new OutboxWriter(store, CLOCK));
    assertThrows(
        IllegalStateException.class,
        () -> events.enqueue("orders", "event-1", "created.v1", "order", "o-1", "{}"));
    verify(store, never()).append(any());
    try (var ignored = RequestContext.open(METADATA)) {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      var envelope = events.enqueue("orders", "event-1", "created.v1", "order", "o-1", "{}");
      assertEquals("tenant-a", envelope.extensions().get(EventEnvelope.TENANT));
      assertEquals("payment.created.v1", envelope.type());
      assertEquals("o-1", envelope.subject());
      var record = org.mockito.ArgumentCaptor.forClass(pe.axiz.core.outbox.OutboxRecord.class);
      verify(store).append(record.capture());
      assertEquals("event-1", record.getValue().id());
      assertEquals("orders", record.getValue().destination());
    } finally {
      TransactionSynchronizationManager.clear();
    }
  }

  @Test
  void reactiveWritesAreColdAndRequireAnActiveSubscriberTransaction() {
    var written = new AtomicReference<EventEnvelope>();
    ReactiveOutboxWriter writer =
        (destination, envelope, aggregate) -> Mono.fromRunnable(() -> written.set(envelope));
    var events = new ReactiveTransactionalEvents(factory(), writer);
    var work = events.enqueue("orders", "event-2", "created.v1", "order", "o-2", "{}");
    assertThat(written).hasNullValue();
    assertThrows(IllegalStateException.class, () -> work.block(Duration.ofSeconds(2)));
    assertThrows(
        IllegalStateException.class,
        () ->
            work.contextWrite(TransactionContextManager.createTransactionContext())
                .block(Duration.ofSeconds(2)));
    var result =
        org.springframework.transaction.reactive.TransactionSynchronizationManager
            .forCurrentTransaction()
            .doOnNext(tx -> tx.setActualTransactionActive(true))
            .then(work)
            .contextWrite(context -> context.put(RequestContext.KEY, METADATA))
            .contextWrite(TransactionContextManager.createTransactionContext())
            .block(Duration.ofSeconds(2));
    assertThat(result).isSameAs(written.get());
    assertEquals("tenant-a", result.extensions().get(EventEnvelope.TENANT));
    assertEquals("correlation-123", result.extensions().get(EventEnvelope.CORRELATION_ID));
  }

  @Test
  void configurationRejectsMissingOrInvalidStoresAndWiresTheSelectedPorts() {
    var context =
        new ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    AxizOutboxAutoConfiguration.class, OutboxBlockedMetrics.class));
    context.run(c -> assertThat(c).hasFailed());
    context
        .withPropertyValues("axiz.outbox.enabled=false")
        .run(c -> assertThat(c).doesNotHaveBean(TransactionalEvents.class));
    var store = mock(OutboxStore.class);
    doThrow(new IllegalStateException("wrong schema")).when(store).verifySchema();
    context.withBean(OutboxStore.class, () -> store).run(c -> assertThat(c).hasFailed());

    var valid = mock(OutboxStore.class);
    var meters = new SimpleMeterRegistry();
    when(valid.countBlockedAggregates()).thenReturn(2L);
    when(valid.countSkipped()).thenReturn(3L);
    context
        .withBean(OutboxStore.class, () -> valid)
        .withBean(EventFactory.class, OutboxCompositionTest::factory)
        .withBean(OutboxWriter.class, () -> new OutboxWriter(valid, CLOCK))
        .withBean(ReactiveOutboxWriter.class, () -> (destination, event, aggregate) -> Mono.empty())
        .withBean(SimpleMeterRegistry.class, () -> meters)
        .withBean(EventPublisher.class, () -> mock(EventPublisher.class))
        .withBean(
            OutboxRelayTransactions.class,
            () ->
                new OutboxRelayTransactions(
                    new TransactionRunner() {
                      public <T> T inTransaction(Callable<T> work) throws Exception {
                        return work.call();
                      }
                    }))
        .withPropertyValues(
            "axiz.outbox.relay.publish-budget=1s", "axiz.outbox.relay.poll-interval=1h")
        .run(
            c -> {
              assertThat(c)
                  .hasNotFailed()
                  .hasSingleBean(TransactionalEvents.class)
                  .hasSingleBean(ReactiveTransactionalEvents.class);
              verify(valid).verifySchema();
              assertThat(c.getBean(OutboxRelayRunner.class).isRunning()).isTrue();
              c.getBean(OutboxRelayRunner.class).stop();
              long priorClaims =
                  meters.find("axiz.outbox.relay.claim.duration").timer() == null
                      ? 0
                      : meters.get("axiz.outbox.relay.claim.duration").timer().count();
              var listener = c.getBean(OutboxRelayListener.class);
              listener.onClaim(Duration.ofMillis(25), 2);
              listener.onBudgetExhausted(1);
              assertEquals(
                  priorClaims + 1, meters.get("axiz.outbox.relay.claim.duration").timer().count());
              assertEquals(1, meters.get("axiz.outbox.relay.budget.exhausted").counter().count());
              c.getBeansOfType(MeterBinder.class).values().forEach(binder -> binder.bindTo(meters));
              assertThat(meters.get("axiz.outbox.blocked.aggregates").gauge()).isNotNull();
              assertThat(meters.get("axiz.outbox.skipped").gauge()).isNotNull();
            });
    meters.close();
  }
}
