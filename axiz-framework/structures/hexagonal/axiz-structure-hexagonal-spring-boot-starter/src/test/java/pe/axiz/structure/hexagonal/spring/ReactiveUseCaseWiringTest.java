package pe.axiz.structure.hexagonal.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.ReactiveTransactionManager;
import pe.axiz.adapter.outbox.r2dbc.R2dbcOutboxStore;
import pe.axiz.adapter.outbox.r2dbc.R2dbcOutboxWriter;
import pe.axiz.core.domain.DomainEvent;
import pe.axiz.core.id.IdGenerator;
import pe.axiz.core.messaging.EventFactory;
import pe.axiz.core.messaging.PayloadCodec;
import pe.axiz.kit.r2dbc.ReactiveSql;
import pe.axiz.kit.r2dbc.SqlParams;
import pe.axiz.spring.outbox.ReactiveTransactionalEvents;
import pe.axiz.spring.r2dbc.DatabaseClientReactiveSql;
import pe.axiz.spring.test.BlockHoundExtension;
import pe.axiz.structure.hexagonal.AggregateEvent;
import pe.axiz.structure.hexagonal.ReactiveDomainEventSink;
import pe.axiz.structure.hexagonal.ReactiveUseCase;
import pe.axiz.structure.hexagonal.annotation.UseCase;
import pe.axiz.test.support.r2dbc.FakeR2dbc;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

@ExtendWith(BlockHoundExtension.class)
class ReactiveUseCaseWiringTest {
  static final Duration T = Duration.ofSeconds(5);
  static final AtomicInteger CALLS = new AtomicInteger();

  record Changed(String eventId, Instant occurredAt, String aggregateId) implements AggregateEvent {
    @Override
    public String aggregateType() {
      return "payment";
    }
  }

  record Unclassified(String eventId, Instant occurredAt, String aggregateId)
      implements DomainEvent {}

  @UseCase("change-payment")
  public static class Change implements ReactiveUseCase<String, String> {
    private final ReactiveSql sql;
    private final ReactiveDomainEventSink events;

    Change(ReactiveSql sql, ReactiveDomainEventSink events) {
      this.sql = sql;
      this.events = events;
    }

    public String description() {
      return "payment change";
    }

    @Override
    public Mono<String> execute(String mode) {
      CALLS.incrementAndGet();
      if (mode.equals("throw")) throw new IllegalStateException("submission failed");
      if (mode.equals("null")) return null;
      return sql.update("UPDATE payments SET status = 'PAID'", SqlParams.of())
          .publishOn(Schedulers.parallel())
          .then(
              Mono.from(
                  events.publish(
                      List.of(
                          new Changed("e-" + mode, Instant.EPOCH, "p1"),
                          new Unclassified("u-" + mode, Instant.EPOCH, "p1")))))
          .then(
              Mono.defer(
                  () ->
                      switch (mode) {
                        case "error" -> Mono.error(new IllegalStateException("business failed"));
                        case "cancel" -> Mono.never();
                        default -> Mono.just(mode);
                      }));
    }
  }

  @UseCase(readOnly = true)
  public static class Lookup implements ReactiveUseCase<String, String> {
    @Override
    public Mono<String> execute(String input) {
      return Mono.just(input);
    }
  }

  @UseCase
  public static class FluxLookup implements ReactiveUseCase<String, String> {
    @Override
    public Flux<String> execute(String input) {
      return switch (input) {
        case "multiple" -> Flux.just("one", "two");
        case "late-error" ->
            Flux.concat(Flux.just("one"), Flux.error(new IllegalStateException("late failure")));
        case "empty" -> Flux.empty();
        default -> Flux.just(input);
      };
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class Cases {
    @Bean
    Change change(ReactiveSql sql, ReactiveDomainEventSink sink) {
      return new Change(sql, sink);
    }
  }

  private static ReactiveWebApplicationContextRunner runner() {
    return new ReactiveWebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AopAutoConfiguration.class,
                AxizHexagonalAutoConfiguration.class,
                AxizReactiveHexagonalAutoConfiguration.class));
  }

  private static ReactiveWebApplicationContextRunner transactional(FakeR2dbc db) {
    ReactiveSql sql = new DatabaseClientReactiveSql(DatabaseClient.create(db));
    PayloadCodec codec =
        new PayloadCodec() {
          @Override
          public String contentType() {
            return "text/plain";
          }

          @Override
          public byte[] encode(Object value) {
            return new byte[] {1};
          }

          @Override
          public <V> V decode(byte[] value, Class<V> type) {
            throw new UnsupportedOperationException();
          }
        };
    var events =
        new ReactiveTransactionalEvents(
            new EventFactory("urn:original", "", codec, IdGenerator.uuidV7(), Clock.systemUTC()),
            new R2dbcOutboxWriter(new R2dbcOutboxStore(sql, "axiz_outbox", T), Clock.systemUTC()));
    return runner()
        .withUserConfiguration(Cases.class)
        .withBean(ReactiveSql.class, () -> sql)
        .withBean(ReactiveTransactionManager.class, () -> new R2dbcTransactionManager(db))
        .withBean(ReactiveTransactionalEvents.class, () -> events);
  }

  private static FakeR2dbc database() {
    var sequence = new AtomicLong();
    return new FakeR2dbc()
        .onQuery(
            "INSERT INTO axiz_outbox_aggregate",
            values -> List.of(Map.of("last_seq", sequence.incrementAndGet())))
        .onUpdate("UPDATE payments", values -> 1L)
        .onUpdate("INSERT INTO axiz_outbox", values -> 1L);
  }

  @Test
  void autoconfigurationDefersEachSubscriptionAndUsesTheSameConnectionForOutbox() {
    var db = database();
    CALLS.set(0);
    transactional(db)
        .withPropertyValues("axiz.hexagonal.events.destinations.changed=payment-changes")
        .run(
            context -> {
              assertNotNull(context.getBean(ReactiveDomainEventSink.class));
              Change change = context.getBean(Change.class);
              assertTrue(AopUtils.isAopProxy(change));
              assertEquals(
                  "payment change",
                  change.description(),
                  "plain helpers must not be replaced with a Mono");
              Mono<String> work = change.execute("paid");
              assertEquals(0, CALLS.get());
              assertEquals("paid", work.subscribeOn(Schedulers.parallel()).block(T));
              assertEquals("paid", work.subscribeOn(Schedulers.parallel()).block(T));
              assertEquals(2, CALLS.get());
              int connection = db.last("UPDATE payments").connection();
              assertEquals(connection, db.last("INSERT INTO axiz_outbox").connection());
              assertTrue(db.events().contains("commit:" + connection));
            });
  }

  @Test
  void errorsAndCancellationRollBackAndAreNotTurnedIntoSuccess() {
    var db = database();
    transactional(db)
        .run(
            context -> {
              Change change = context.getBean(Change.class);
              assertEquals(
                  "business failed",
                  assertThrows(
                          IllegalStateException.class,
                          () -> change.execute("error").subscribeOn(Schedulers.parallel()).block(T))
                      .getMessage());
              assertTrue(db.events().stream().anyMatch(event -> event.startsWith("rollback:")));
              var cancelled =
                  change.execute("cancel").subscribeOn(Schedulers.parallel()).toFuture();
              // Se espera a que el fake del protocolo, acotado y solo de CPU, registre la escritura
              // en el outbox antes de cancelar.
              StepVerifier.create(
                      Mono.defer(() -> Mono.just(db.executions().size()))
                          .repeatWhen(repeats -> repeats.delayElements(Duration.ofMillis(10)))
                          .filter(size -> size >= 6)
                          .next())
                  .expectNextCount(1)
                  .expectComplete()
                  .verify(T);
              cancelled.cancel(true);
              assertTrue(cancelled.isCancelled());
            });
  }

  @Test
  void synchronousFailureAndNullPublisherAreDeferredErrors() {
    transactional(database())
        .run(
            context -> {
              Change change = context.getBean(Change.class);
              Mono<String> submission = change.execute("throw");
              assertEquals(
                  "submission failed",
                  assertThrows(IllegalStateException.class, () -> submission.block(T))
                      .getMessage());
              assertTrue(
                  assertThrows(IllegalStateException.class, () -> change.execute("null").block(T))
                      .getMessage()
                      .contains("Publisher"));
            });
  }

  @Test
  void readOnlyUseCasesAlsoWorkWithoutATransactionManagerAndContinueObservationParent() {
    runner()
        .withBean(Lookup.class)
        .run(
            context -> {
              assertTrue(AopUtils.isAopProxy(context.getBean(Lookup.class)));
              Observation parent = Observation.start("parent", ObservationRegistry.NOOP);
              try {
                assertEquals(
                    "found",
                    context
                        .getBean(Lookup.class)
                        .execute("found")
                        .contextWrite(Context.of("micrometer.observation", parent))
                        .block(T));
              } finally {
                parent.stop();
              }
            });
  }

  @Test
  void covariantFluxRetainsItsTypeAndEnforcesTheSingleResultContract() {
    runner()
        .withBean(FluxLookup.class)
        .run(
            context -> {
              FluxLookup lookup = context.getBean(FluxLookup.class);
              assertEquals(List.of("found"), lookup.execute("found").collectList().block(T));
              assertEquals(List.of(), lookup.execute("empty").collectList().block(T));
              StepVerifier.create(lookup.execute("multiple"))
                  .expectError(IndexOutOfBoundsException.class)
                  .verify(T);
              StepVerifier.create(lookup.execute("late-error"))
                  .expectErrorMessage("late failure")
                  .verify(T);
            });
  }

  @Test
  void aNonWebWorkerStartsAndCommitsAReactiveTransactionForEachSubscription() {
    var db = database();
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AopAutoConfiguration.class,
                AxizHexagonalAutoConfiguration.class,
                AxizReactiveHexagonalAutoConfiguration.class))
        .withBean(ReactiveTransactionManager.class, () -> new R2dbcTransactionManager(db))
        .withBean(Lookup.class)
        .run(
            context -> {
              var worker = context.getBean(Lookup.class);
              assertTrue(AopUtils.isAopProxy(worker));
              assertEquals("worker-result", worker.execute("worker-result").block(T));
              assertTrue(db.events().stream().anyMatch(e -> e.startsWith("commit:")));
            });
  }

  @Test
  void explicitReactiveSinkTakesPrecedence() {
    ReactiveDomainEventSink custom = events -> Mono.empty();
    transactional(database())
        .withBean(ReactiveDomainEventSink.class, () -> custom)
        .run(
            context -> {
              Assertions.assertSame(custom, context.getBean(ReactiveDomainEventSink.class));
            });
  }
}
