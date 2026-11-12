package pe.axiz.spring.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.transaction.reactive.TransactionContextManager;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import pe.axiz.adapter.outbox.r2dbc.R2dbcOutboxStore;
import pe.axiz.adapter.outbox.r2dbc.ReactiveOutboxWriter;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditPublishException;
import pe.axiz.core.audit.AuditPublisher;
import pe.axiz.core.audit.AuditTrail;
import pe.axiz.core.audit.Auditable;
import pe.axiz.core.audit.QueuedAsyncAuditPublisher;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.commons.RequestMetadata;
import pe.axiz.core.id.IdGenerator;
import pe.axiz.core.sensitive.ObjectMasker;
import pe.axiz.kit.r2dbc.ReactiveSql;
import pe.axiz.kit.r2dbc.RowMapper;
import pe.axiz.kit.r2dbc.SqlParams;
import pe.axiz.kit.r2dbc.SqlRow;
import pe.axiz.spring.foundation.ReactiveInfrastructureBootstrap;
import pe.axiz.spring.foundation.aws.AxizAwsIdentityAutoConfiguration;
import pe.axiz.spring.test.BlockHoundExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

@ExtendWith(BlockHoundExtension.class)
class NativeAuditTest {
  private static final Duration T = Duration.ofSeconds(5);

  @BeforeAll
  static void warm() {
    ReactiveInfrastructureBootstrap.initialize();
  }

  @AfterEach
  void noOriginalBlockingSites() {
    assertThat(BlockHoundExtension.detectedBlockingCalls()).isEmpty();
  }

  @Test
  void aSaturatedQueueNeverWaitsForFallbackOnTheReactiveCaller() throws Exception {
    List<AuditEvent> captured = new CopyOnWriteArrayList<>();
    new AuditTrail(
            "service",
            captured::add,
            ObjectMasker.defaults(),
            IdGenerator.uuidV7(),
            Clock.systemUTC())
        .record("operation", "account")
        .success();
    var event = captured.getFirst();
    var delivery = new CompletableFuture<Void>();
    var entered = new CountDownLatch(1);
    var fallbackEntered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var publisher =
        new QueuedAsyncAuditPublisher(
            e -> {
              entered.countDown();
              return delivery;
            },
            e -> {
              fallbackEntered.countDown();
              try {
                release.await(5, TimeUnit.SECONDS);
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
            },
            1,
            Duration.ofSeconds(5))) {
      try {
        publisher.publish(event);
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        publisher.publish(event);
        StepVerifier.create(
                Mono.defer(() -> Mono.fromCompletionStage(publisher.publish(event)))
                    .subscribeOn(Schedulers.parallel()))
            .verifyComplete();
        assertTrue(fallbackEntered.await(2, TimeUnit.SECONDS));
        assertEquals(1, publisher.queue().fallbackCount());
        assertEquals(
            1, release.getCount(), "Reactive admission completed while fallback was still blocked");
      } finally {
        release.countDown();
        delivery.complete(null);
      }
    }
  }

  public static class Actions {
    final AtomicInteger calls = new AtomicInteger();

    @Auditable(
        action = "account.open",
        resource = "account",
        resourceIdArgument = "id",
        captureResult = true)
    public Mono<String> open(String id) {
      return Mono.defer(
          () -> {
            calls.incrementAndGet();
            return Mono.just(id);
          });
    }

    @Auditable(action = "account.fail", resource = "account")
    public Mono<String> fail(RuntimeException failure) {
      return Mono.error(failure);
    }

    @Auditable(action = "account.lines", resource = "account")
    public Flux<Integer> lines() {
      return Flux.just(1, 2);
    }

    @Auditable(action = "account.empty", resource = "account")
    public Mono<Void> empty() {
      return Mono.empty();
    }

    @Auditable(action = "account.cancel", resource = "account")
    public Mono<Void> never() {
      return Mono.never();
    }

    @Auditable(action = "account.invalid", resource = "account")
    public String invalid() {
      return "invalid";
    }
  }

  @Test
  void nativeAdviceIsDeferredAndEachSubscriptionUsesItsOwnCallerAndRecord() {
    List<AuditEvent> events = new CopyOnWriteArrayList<>();
    var target = new Actions();
    var proxy =
        proxy(
            target,
            trail(
                event -> {
                  events.add(event);
                  return Mono.empty();
                }));
    var result = proxy.open("a");
    assertEquals(0, target.calls.get());
    assertTrue(events.isEmpty());
    for (String caller : List.of("A", "B")) {
      StepVerifier.create(
              result
                  .contextWrite(ctx -> ctx.put(RequestContext.KEY, metadata(caller)))
                  .subscribeOn(Schedulers.parallel()))
          .expectNext("a")
          .expectComplete()
          .verify(T);
    }
    assertEquals(2, target.calls.get());
    assertEquals(List.of("A", "B"), events.stream().map(AuditEvent::correlationId).toList());
    assertFalse(events.getFirst().id().equals(events.getLast().id()));
    assertEquals("a", events.getFirst().resourceId());
    assertEquals("a", events.getFirst().details().get("result"));
    assertEquals("user", events.getFirst().actor().subject());
    assertThat(Assertions.assertThrows(IllegalStateException.class, proxy::invalid))
        .hasMessageContaining("requires Mono or Flux");
  }

  @Test
  void terminalSignalsAwaitDeliveryAndCancellationStopsTheNativeSend() {
    CountDownLatch transportCancelled = new CountDownLatch(1);
    CompletableFuture<Void> sdk =
        new CompletableFuture<>() {
          @Override
          public boolean cancel(boolean interrupt) {
            boolean cancelled = super.cancel(interrupt);
            transportCancelled.countDown();
            return cancelled;
          }
        };
    CountDownLatch submitted = new CountDownLatch(1);
    var proxy =
        proxy(
            new Actions(),
            trail(
                ReactiveAuditPublisher.from(
                    event -> {
                      submitted.countDown();
                      return sdk;
                    })));
    StepVerifier.create(proxy.open("a").subscribeOn(Schedulers.parallel()))
        .expectSubscription()
        .then(
            () -> {
              try {
                assertTrue(submitted.await(5, TimeUnit.SECONDS));
              } catch (InterruptedException error) {
                throw new AssertionError(error);
              }
            })
        .thenCancel()
        .verify(T);
    try {
      assertTrue(transportCancelled.await(5, TimeUnit.SECONDS));
    } catch (InterruptedException error) {
      throw new AssertionError(error);
    }
    assertTrue(sdk.isCancelled());
    List<AuditEvent> events = new CopyOnWriteArrayList<>();
    proxy =
        proxy(
            new Actions(),
            trail(
                event -> {
                  events.add(event);
                  return Mono.empty();
                }));
    StepVerifier.create(proxy.lines().subscribeOn(Schedulers.parallel()))
        .expectNext(1, 2)
        .expectComplete()
        .verify(T);
    StepVerifier.create(proxy.empty().subscribeOn(Schedulers.parallel()))
        .expectComplete()
        .verify(T);
    assertEquals(2, events.size());
    assertEquals(AuditEvent.Outcome.SUCCESS, events.getLast().outcome());
  }

  @Test
  void failureIsPreservedAndCancellationRecordsOneFailure() throws Exception {
    List<AuditEvent> events = new CopyOnWriteArrayList<>();
    var proxy =
        proxy(
            new Actions(),
            trail(
                event -> {
                  events.add(event);
                  return Mono.empty();
                }));
    var original = new IllegalArgumentException("business");
    StepVerifier.create(proxy.fail(original).subscribeOn(Schedulers.parallel()))
        .expectErrorMatches(e -> e == original)
        .verify(T);
    assertEquals("IllegalArgumentException", events.getFirst().errorCode());
    events.clear();
    CountDownLatch cleanup = new CountDownLatch(1);
    proxy =
        proxy(
            new Actions(),
            trail(
                event -> {
                  events.add(event);
                  cleanup.countDown();
                  return Mono.empty();
                }));
    StepVerifier.create(proxy.never().subscribeOn(Schedulers.parallel()))
        .expectSubscription()
        .expectNoEvent(Duration.ofMillis(30))
        .thenCancel()
        .verify(T);
    assertTrue(cleanup.await(5, TimeUnit.SECONDS));
    assertEquals(1, events.size());
    assertEquals("CancellationException", events.getFirst().errorCode());
    var rejected =
        proxy(new Actions(), trail(event -> Mono.error(new IllegalStateException("sink down"))));
    StepVerifier.create(rejected.fail(original))
        .expectErrorMatches(e -> e == original && e.getSuppressed().length == 1)
        .verify(T);
    StepVerifier.create(rejected.open("a"))
        .expectErrorMatches(e -> e instanceof IllegalStateException)
        .verify(T);
  }

  @Test
  void httpLifecycleDistinguishesErrorsStatusesAndCancellation() throws Exception {
    List<AuditEvent> events = new CopyOnWriteArrayList<>();
    CountDownLatch cleanup = new CountDownLatch(1);
    var trail =
        trail(
            event -> {
              events.add(event);
              if (events.size() == 4) cleanup.countDown();
              return Mono.empty();
            });
    var filter =
        new HttpAuditWebFilter(
            trail,
            AuditContextResolver.fromRequestContext(),
            new AxizAuditProperties.Http(true, List.of("POST"), List.of("/**"), List.of()));
    var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/accounts").build());
    var original = new IllegalStateException("failure");
    StepVerifier.create(
            filter
                .filter(exchange, ignored -> Mono.error(original))
                .subscribeOn(Schedulers.parallel()))
        .expectErrorMatches(e -> e == original)
        .verify(T);
    assertEquals(AuditEvent.Outcome.FAILURE, events.getFirst().outcome());
    assertEquals(500, events.getFirst().details().get("status"));
    exchange.getResponse().setStatusCode(HttpStatus.CONFLICT);
    StepVerifier.create(
            filter.filter(exchange, ignored -> Mono.empty()).subscribeOn(Schedulers.parallel()))
        .expectComplete()
        .verify(T);
    assertEquals("HTTP-409", events.getLast().errorCode());
    exchange.getResponse().setStatusCode(HttpStatus.CREATED);
    StepVerifier.create(
            filter
                .filter(exchange, ignored -> Mono.empty())
                .contextWrite(ctx -> ctx.put(RequestContext.KEY, metadata("http")))
                .subscribeOn(Schedulers.parallel()))
        .expectComplete()
        .verify(T);
    assertEquals("http", events.getLast().correlationId());
    assertEquals(AuditEvent.Outcome.SUCCESS, events.getLast().outcome());
    StepVerifier.create(
            filter.filter(exchange, ignored -> Mono.never()).subscribeOn(Schedulers.parallel()))
        .expectSubscription()
        .expectNoEvent(Duration.ofMillis(30))
        .thenCancel()
        .verify(T);
    // The subscriber's cancellation runs async cleanup; verify the event, not just downstream
    // cancel.
    assertTrue(cleanup.await(5, TimeUnit.SECONDS));
    assertEquals(4, events.size());
    assertEquals("CancellationException", events.getLast().errorCode());
  }

  @Test
  void reactiveConfigurationRejectsImperativeSinksAndHonorsDisableAndNativeOverrides() {
    var runner =
        new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
            .withPropertyValues("axiz.config.environment=test")
            .withConfiguration(
                AutoConfigurations.of(
                    AxizAuditAutoConfiguration.class, AxizNativeAuditAutoConfiguration.class));
    runner
        .withBean(
            AsyncAuditSink.class,
            () -> new AsyncAuditSink("memory", event -> CompletableFuture.completedFuture(null)))
        .run(
            ctx -> {
              assertThat(ctx)
                  .hasNotFailed()
                  .hasSingleBean(ReactiveAuditTrail.class)
                  .doesNotHaveBean(AuditTrail.class)
                  .doesNotHaveBean(AuditPublisher.class);
              assertThat(ctx.getBean("axizReactiveAuditableAdvisor", Advisor.class)).isNotNull();
            });
    runner
        .withBean(AuditSink.class, () -> new AuditSink("blocking", event -> {}))
        .run(ctx -> assertThat(ctx).hasFailed());
    runner
        .withPropertyValues("axiz.audit.enabled=false")
        .run(ctx -> assertThat(ctx).doesNotHaveBean(ReactiveAuditTrail.class));
    runner
        .withPropertyValues("axiz.audit.mode=DURABLE", "axiz.outbox.relay.enabled=false")
        .run(ctx -> assertThat(ctx).hasFailed());
    runner.run(ctx -> assertThat(ctx).hasNotFailed());
  }

  @Test
  void durableFacadeRetainsReactorTransactionAndFailureFallback() {
    AtomicInteger writes = new AtomicInteger();
    AtomicInteger fallbacks = new AtomicInteger();
    ReactiveSql sql =
        new ReactiveSql() {
          public Mono<Long> update(String statement, SqlParams params) {
            return TransactionSynchronizationManager.forCurrentTransaction()
                .map(
                    manager -> {
                      assertTrue(manager.isActualTransactionActive());
                      writes.incrementAndGet();
                      return 1L;
                    });
          }

          public <T> Flux<T> query(String statement, SqlParams params, RowMapper<T> mapper) {
            return Flux.defer(
                () ->
                    Flux.just(
                        mapper.map(
                            new SqlRow() {
                              public <V> V get(String column, Class<V> type) {
                                return type.cast(1L);
                              }
                            })));
          }
        };
    var publisher =
        new ReactiveTransactionalAuditPublisher(
            new ReactiveOutboxWriter(
                new R2dbcOutboxStore(sql, "axiz_outbox", T), Clock.systemUTC()),
            "audit",
            event -> Mono.fromRunnable(fallbacks::incrementAndGet),
            AuditOutsideTransaction.FAIL);
    var trail = trail(publisher);
    StepVerifier.create(trail.record("a", "r").success())
        .expectError(AuditPublishException.class)
        .verify(T);
    Mono<Void> active =
        TransactionSynchronizationManager.forCurrentTransaction()
            .doOnNext(manager -> manager.setActualTransactionActive(true))
            .then(trail.record("a", "r").success())
            .then(trail.record("a", "r").failure("BUSINESS"));
    StepVerifier.create(
            active
                .contextWrite(TransactionContextManager.createTransactionContext())
                .subscribeOn(Schedulers.parallel()))
        .expectComplete()
        .verify(T);
    assertEquals(1, writes.get());
    assertEquals(1, fallbacks.get());
    var fallback =
        new ReactiveTransactionalAuditPublisher(
            new ReactiveOutboxWriter(
                new R2dbcOutboxStore(sql, "axiz_outbox", T), Clock.systemUTC()),
            "audit",
            event -> Mono.fromRunnable(fallbacks::incrementAndGet),
            AuditOutsideTransaction.ASYNC);
    StepVerifier.create(trail(fallback).record("a", "r").success()).expectComplete().verify(T);
    assertEquals(2, fallbacks.get());
  }

  private static RequestMetadata metadata(String correlation) {
    return RequestMetadata.builder(correlation, "request").subject("user").build();
  }

  private static ReactiveAuditTrail trail(ReactiveAuditPublisher publisher) {
    return new ReactiveAuditTrail(
        "svc", publisher, ObjectMasker.defaults(), IdGenerator.uuidV7(), Clock.systemUTC());
  }

  private static Actions proxy(Actions target, ReactiveAuditTrail trail) {
    ProxyFactory factory = new ProxyFactory(target);
    factory.setProxyTargetClass(true);
    factory.addAdvice(
        new ReactiveAuditableInterceptor(() -> trail, AuditContextResolver::fromRequestContext));
    return (Actions) factory.getProxy();
  }
}
