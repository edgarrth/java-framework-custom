package pe.axiz.extension.cqrs.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditTrail;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.context.RequestMetadata;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.id.IdGenerator;
import pe.axiz.core.idempotency.AsyncIdempotencyService;
import pe.axiz.core.idempotency.AsyncIdempotencyStore;
import pe.axiz.core.idempotency.IdempotencyErrors;
import pe.axiz.core.idempotency.IdempotencyRecord;
import pe.axiz.core.idempotency.IdempotencyService;
import pe.axiz.core.idempotency.InMemoryAsyncIdempotencyStore;
import pe.axiz.core.idempotency.InMemoryIdempotencyStore;
import pe.axiz.core.idempotency.StoredResponse;
import pe.axiz.core.sensitive.ObjectMasker;
import pe.axiz.extension.cqrs.AccessPolicies;
import pe.axiz.extension.cqrs.AccessPolicy;
import pe.axiz.extension.cqrs.AuditedCommand;
import pe.axiz.extension.cqrs.Caller;
import pe.axiz.extension.cqrs.IdempotentCommand;
import pe.axiz.extension.cqrs.Invocation;
import pe.axiz.extension.cqrs.OpenAccess;
import pe.axiz.extension.cqrs.ReactiveCommandHandler;
import pe.axiz.spring.audit.ReactiveAuditPublisher;
import pe.axiz.spring.audit.ReactiveAuditTrail;
import pe.axiz.spring.foundation.ReactiveInfrastructureBootstrap;
import pe.axiz.spring.test.BlockHoundExtension;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(BlockHoundExtension.class)
class NativeReactiveCqrsTest {
  private static final Duration T = Duration.ofSeconds(5);

  @BeforeAll
  static void warm() {
    ReactiveInfrastructureBootstrap.initialize();
  }

  @AfterEach
  void noBlocking() {
    assertThat(BlockHoundExtension.detectedBlockingCalls()).isEmpty();
  }

  @OpenAccess(reason = "Native pipeline regression fixture")
  public record Action(String idempotencyKey)
      implements IdempotentCommand<String>, AuditedCommand<String> {
    public String auditAction() {
      return "account.create";
    }

    public String auditResource() {
      return "account";
    }

    public String auditResourceId() {
      return "a";
    }
  }

  static final class Handler implements ReactiveCommandHandler<Action, String> {
    public Mono<String> handle(Action command) {
      return Mono.just("ok");
    }
  }

  @Test
  void pipelineIsNativeAndReplayDoesNotRepeatTheAudit() {
    List<AuditEvent> events = new CopyOnWriteArrayList<>();
    var idempotency =
        new ReactiveBehaviors.Idempotency(
            service(new InMemoryAsyncIdempotencyStore(Clock.systemUTC())),
            JsonMapper.builder().build());
    var audit =
        new ReactiveBehaviors.Audit(
            trail(
                event -> {
                  events.add(event);
                  return Mono.empty();
                }));
    var invocation =
        new Invocation<>(new Action("native-idempotency-key-0001"), "create", String.class);
    AtomicInteger handled = new AtomicInteger();
    Mono<String> work =
        idempotency
            .handle(
                invocation,
                () ->
                    audit.handle(
                        invocation,
                        () -> Mono.fromSupplier(() -> "created-" + handled.incrementAndGet())))
            .contextWrite(
                c ->
                    c.put(
                        RequestContext.KEY,
                        RequestMetadata.builder("corr", "request")
                            .subject("user")
                            .tenantId("tenant")
                            .build()));
    StepVerifier.create(work.subscribeOn(Schedulers.parallel()))
        .expectNext("created-1")
        .expectComplete()
        .verify(T);
    StepVerifier.create(work.subscribeOn(Schedulers.parallel()))
        .expectNext("created-1")
        .expectComplete()
        .verify(T);
    assertEquals(1, handled.get());
    assertEquals(1, events.size());
    assertEquals("corr", events.getFirst().correlationId());
    assertEquals("user", events.getFirst().actor().subject());
  }

  @Test
  void cancellationCancelsNativeCompletionAndRetainsAnUncertainOutcome() throws Exception {
    Store store = new Store();
    var behavior = new ReactiveBehaviors.Idempotency(service(store), JsonMapper.builder().build());
    var invocation =
        new Invocation<>(new Action("native-idempotency-key-0002"), "create", String.class);
    StepVerifier.create(
            behavior.handle(invocation, () -> Mono.just("ok")).subscribeOn(Schedulers.parallel()))
        .then(
            () -> {
              try {
                assertTrue(store.completing.await(5, TimeUnit.SECONDS));
              } catch (InterruptedException error) {
                throw new AssertionError(error);
              }
            })
        .thenCancel()
        .verify(T);
    assertTrue(store.pending.isCancelled());
    assertTrue(store.released.await(5, TimeUnit.SECONDS));
    // Cancellation cannot prove absence of an effect; the same command remains reserved.
    store.holdCompletion = false;
    StepVerifier.create(
            behavior
                .handle(invocation, () -> Mono.just("retry"))
                .subscribeOn(Schedulers.parallel()))
        .expectErrorSatisfies(
            error -> {
              assertTrue(error instanceof BusinessException);
              assertEquals(
                  IdempotencyErrors.OUTCOME_UNKNOWN.code(), ((BusinessException) error).code());
            })
        .verify(T);
  }

  @Test
  void imperativeConstructorsFailBeforeCallingAnyIo() {
    var imperative =
        new IdempotencyService(
            new InMemoryIdempotencyStore(),
            Duration.ofSeconds(30),
            Duration.ofHours(1),
            Clock.systemUTC());
    assertThrows(
        IllegalArgumentException.class,
        () -> new ReactiveBehaviors.Idempotency(imperative, JsonMapper.builder().build()));
    var syncTrail =
        new AuditTrail(
            "svc",
            event -> {
              throw new AssertionError("imperative publisher invoked");
            },
            ObjectMasker.defaults(),
            IdGenerator.uuidV7(),
            Clock.systemUTC());
    assertThrows(IllegalArgumentException.class, () -> new ReactiveBehaviors.Audit(syncTrail));
    var blocking =
        new AccessPolicy<Action>() {
          public Class<Action> requestType() {
            return Action.class;
          }

          public void check(Action request, Caller caller) {
            throw new AssertionError("imperative policy invoked");
          }
        };
    assertThrows(
        IllegalArgumentException.class,
        () -> new ReactiveBehaviors.Authorization(new AccessPolicies(List.of(blocking))));
  }

  @Test
  void missingNativePortsFailStartupBeforeServingAuditedOrIdempotentCommands() {
    var runner =
        new ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    AxizCqrsAutoConfiguration.class, AxizReactiveCqrsAutoConfiguration.class))
            .withBean(Handler.class, Handler::new);
    runner.run(ctx -> assertThat(ctx).hasFailed());
    var withIdempotency =
        runner.withBean(
            AsyncIdempotencyService.class,
            () -> service(new InMemoryAsyncIdempotencyStore(Clock.systemUTC())));
    withIdempotency.run(ctx -> assertThat(ctx).hasFailed());
    withIdempotency
        .withBean(ReactiveAuditTrail.class, () -> trail(event -> Mono.empty()))
        .run(
            ctx ->
                assertThat(ctx)
                    .hasNotFailed()
                    .hasSingleBean(ReactiveBehaviors.Idempotency.class)
                    .hasSingleBean(ReactiveBehaviors.Audit.class));
  }

  private static AsyncIdempotencyService service(AsyncIdempotencyStore store) {
    return new AsyncIdempotencyService(
        store, Duration.ofSeconds(30), Duration.ofHours(1), Clock.systemUTC());
  }

  private static ReactiveAuditTrail trail(ReactiveAuditPublisher publisher) {
    return new ReactiveAuditTrail(
        "svc", publisher, ObjectMasker.defaults(), IdGenerator.uuidV7(), Clock.systemUTC());
  }

  private static final class Store implements AsyncIdempotencyStore {
    public CompletionStage<Boolean> compareAndSet(
        IdempotencyRecord expected, @Nullable IdempotencyRecord replacement) {
      return records
          .compareAndSet(expected, replacement)
          .thenApply(
              changed -> {
                if (changed
                    && replacement != null
                    && replacement.status() == IdempotencyRecord.Status.UNKNOWN)
                  released.countDown();
                return changed;
              });
    }

    final InMemoryAsyncIdempotencyStore records =
        new InMemoryAsyncIdempotencyStore(Clock.systemUTC());
    final CompletableFuture<Boolean> pending = new CompletableFuture<>();
    final CountDownLatch completing = new CountDownLatch(1);
    final CountDownLatch released = new CountDownLatch(1);
    volatile boolean holdCompletion = true;

    public CompletionStage<Boolean> tryStart(IdempotencyRecord record) {
      return records.tryStart(record);
    }

    public CompletionStage<Optional<IdempotencyRecord>> find(String key, Instant at) {
      return records.find(key, at);
    }

    public CompletionStage<Boolean> complete(
        String key, String owner, StoredResponse response, Instant expires) {
      if (!holdCompletion) return records.complete(key, owner, response, expires);
      completing.countDown();
      return pending;
    }

    public CompletionStage<Boolean> release(String key, String owner) {
      return records
          .release(key, owner)
          .thenApply(
              result -> {
                released.countDown();
                return result;
              });
    }
  }
}
