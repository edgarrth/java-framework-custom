package pe.axiz.spring.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.ReactiveTransactionManager;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.commons.RequestMetadata;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventHandler;
import pe.axiz.core.messaging.HandlesEvent;
import pe.axiz.spring.foundation.aws.AxizAwsIdentityAutoConfiguration;
import pe.axiz.test.support.r2dbc.FakeR2dbc;
import reactor.core.publisher.Mono;

class ReactiveEdaTest {

  record PaymentPaid(String id) {}

  static final EventEnvelope ENVELOPE =
      new EventEnvelope(
          "e-1",
          "urn:test",
          "pe.axiz.payments.payment-paid.v1",
          Instant.EPOCH,
          null,
          "application/json",
          new byte[0],
          Map.of());

  @HandlesEvent("pe.axiz.payments.payment-paid.v1")
  static class ProjectPayment extends ReactiveTypedEventHandler<PaymentPaid> {
    final DatabaseClient db;
    final AtomicReference<String> correlation = new AtomicReference<>();

    ProjectPayment(DatabaseClient db) {
      this.db = db;
    }

    @Override
    protected Publisher<Void> on(PaymentPaid payload, EventEnvelope envelope) {
      return Mono.deferContextual(
              ctx -> {
                RequestMetadata m = ctx.getOrDefault(RequestContext.KEY, null);
                correlation.set(m == null ? "none" : m.correlationId());
                return db.sql("INSERT INTO payment_history (id) VALUES (:id)")
                    .bind("id", payload.id())
                    .fetch()
                    .rowsUpdated();
              })
          .then();
    }
  }

  @HandlesEvent("pe.axiz.payments.payment-paid.v1")
  static class Stuck extends ReactiveTypedEventHandler<PaymentPaid> {
    @Override
    protected Publisher<Void> on(PaymentPaid payload, EventEnvelope envelope) {
      return Mono.never();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void reactiveHandlersRunInAReactiveTransactionWithTheRequestContext() {
    FakeR2dbc fake = new FakeR2dbc().onUpdate("INSERT INTO payment_history", b -> 1L);
    ProjectPayment handler = new ProjectPayment(DatabaseClient.create(fake));
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
        .withConfiguration(AutoConfigurations.of(AxizMessagingAutoConfiguration.class))
        .withBean(ReactiveTransactionManager.class, () -> new R2dbcTransactionManager(fake))
        .withBean("projectPayment", ProjectPayment.class, () -> handler)
        .run(
            ctx -> {
              EventHandler<PaymentPaid> bean =
                  (EventHandler<PaymentPaid>) ctx.getBean("projectPayment");
              assertInstanceOf(ReactiveTransactionalEventHandler.class, bean);
              assertEquals("pe.axiz.payments.payment-paid.v1", bean.eventType());
              try (RequestContext.Scope s =
                  RequestContext.open(
                      RequestMetadata.builder("corr-12345678", "req-12345678").build())) {
                bean.handle(new PaymentPaid("p-1"), ENVELOPE);
              }
              assertEquals("corr-12345678", handler.correlation.get());
              int connection = fake.last("INSERT INTO payment_history").connection();
              assertTrue(fake.events().contains("begin:" + connection));
              assertTrue(fake.events().contains("commit:" + connection));
            });
  }

  @Test
  @SuppressWarnings("unchecked")
  void handlingIsBounded() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
        .withConfiguration(AutoConfigurations.of(AxizMessagingAutoConfiguration.class))
        .withPropertyValues("axiz.messaging.consumer.handler-timeout=100ms")
        .withBean("stuck", Stuck.class, Stuck::new)
        .run(
            ctx -> {
              EventHandler<PaymentPaid> bean = (EventHandler<PaymentPaid>) ctx.getBean("stuck");
              Exception e =
                  assertThrows(Exception.class, () -> bean.handle(new PaymentPaid("p"), ENVELOPE));
              assertTrue(
                  e instanceof IllegalStateException || e instanceof TimeoutException,
                  e.toString());
            });
  }
}
