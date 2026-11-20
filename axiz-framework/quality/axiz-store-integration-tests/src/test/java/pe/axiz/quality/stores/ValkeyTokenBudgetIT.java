package pe.axiz.quality.stores;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.ClientOptions.DisconnectedBehavior;
import io.lettuce.core.RedisClient;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.GenericContainer;
import pe.axiz.adapter.security.redis.RedisAsyncRateLimiter;
import pe.axiz.adapter.security.redis.RedisRateLimiter;
import pe.axiz.adapter.tokenization.redis.RedisAsyncTokenBudget;
import pe.axiz.adapter.tokenization.redis.RedisTokenBudget;
import pe.axiz.adapter.tokenization.redis.RedisTokenBudgetRecovery;
import pe.axiz.adapter.tokenization.redis.TokenBudgetOwner;
import pe.axiz.core.errors.AxizException;
import pe.axiz.core.errors.RateLimitedException;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenErrors;
import pe.axiz.core.tokenization.TokenOperation;
import pe.axiz.core.tokenization.TokenType;
import pe.axiz.core.tokenization.vault.AsyncTokenBudget;
import pe.axiz.core.tokenization.vault.TokenBudgetPolicy;
import pe.axiz.core.tokenization.vault.TokenBudgetPolicy.Limits;
import pe.axiz.core.tokenization.vault.TokenBudgetPolicy.Windows;
import pe.axiz.core.tokenization.vault.TokenCaller;
import pe.axiz.core.tokenization.vault.VaultScope;
import pe.axiz.quality.containers.TestContainerImages;
import pe.axiz.spring.foundation.ReactiveInfrastructureBootstrap;
import pe.axiz.spring.test.BlockHoundExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/** Actual shared Redis scripts, cancellation-compatible native calls and store outage. */
@ExtendWith(BlockHoundExtension.class)
class ValkeyTokenBudgetIT {
  private static final Duration DEADLINE = Duration.ofSeconds(15);
  private static final TokenDomain DOMAIN = new TokenDomain("billing");
  private static final TokenCaller CALLER = caller("alice", "client-a");
  private static final VaultScope SCOPE =
      new VaultScope(new TokenType("DNI"), "tenant", DOMAIN, "dni", 1);
  private static final Windows HIGH = new Windows(10000, 10000, 10000);
  private static final Limits HIGH_LIMITS = new Limits(HIGH, HIGH, HIGH);
  private static GenericContainer<?> valkey;
  private static RedisClient client;
  private static StatefulRedisConnection<String, String> first;
  private static StatefulRedisConnection<String, String> second;
  private static final AtomicInteger NAMES = new AtomicInteger();
  private static final AtomicInteger ALARMS = new AtomicInteger();

  @BeforeAll
  static void start() {
    Docker.require();
    ReactiveInfrastructureBootstrap.initialize();
    valkey = TestContainerImages.valkey().withExposedPorts(6379);
    valkey.start();
    client = RedisClient.create("redis://127.0.0.1:" + valkey.getMappedPort(6379));
    client.setOptions(
        ClientOptions.builder()
            .timeoutOptions(TimeoutOptions.enabled())
            .requestQueueSize(1000)
            .disconnectedBehavior(DisconnectedBehavior.REJECT_COMMANDS)
            .build());
    first = client.connect();
    second = client.connect();
    first.setTimeout(Duration.ofMillis(500));
    second.setTimeout(Duration.ofMillis(500));
  }

  @AfterAll
  static void stop() {
    try {
      assertAll(
          () ->
              assertTrue(
                  BlockHoundExtension.detectedBlockingCalls().isEmpty(),
                  () -> BlockHoundExtension.detectedBlockingCalls().toString()),
          () -> {
            CompletableFuture.runAsync(
                    () -> {
                      if (first != null) first.close();
                      if (second != null) second.close();
                      if (client != null) client.shutdown();
                    })
                .orTimeout(5, TimeUnit.SECONDS)
                .join();
          });
    } finally {
      if (valkey != null) valkey.stop();
    }
    assertTrue(
        BlockHoundExtension.detectedBlockingCalls().isEmpty(),
        () -> BlockHoundExtension.detectedBlockingCalls().toString());
  }

  private static TokenCaller caller(String subject, String clientId) {
    return new TokenCaller(
        subject, clientId, "tenant", Set.of("SCOPE_tokens:lookup"), Set.of(DOMAIN));
  }

  private static TokenBudgetPolicy policy(Limits query, int concurrency) {
    return TokenBudgetPolicy.builder()
        .query(query)
        .reveal(HIGH_LIMITS)
        .metadata(HIGH_LIMITS)
        .writes(new Limits(new Windows(1, 1, 1), HIGH, HIGH))
        .subjectConcurrent(concurrency)
        .clientConcurrent(concurrency)
        .tenantConcurrent(concurrency)
        .concurrencyRetryAfter(Duration.ofSeconds(2))
        .build();
  }

  private static <T> Mono<T> call(Supplier<CompletionStage<T>> action) {
    return Mono.fromCompletionStage(action).subscribeOn(Schedulers.parallel());
  }

  @Test
  void crashedProcessReservationsNeedTerminalEvidenceAndRecoveryDoesNotFreeOtherOwners()
      throws Exception {
    String namespace = "crash-budget-" + NAMES.incrementAndGet();
    UUID operation = UUID.randomUUID();
    AtomicBoolean terminal = new AtomicBoolean();
    List<RedisTokenBudgetRecovery.Stage> journal = new ArrayList<>();
    Process process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty(
                    "surefire.test.class.path", System.getProperty("java.class.path")),
                CrashingOwner.class.getName(),
                "redis://127.0.0.1:" + valkey.getMappedPort(6379),
                namespace,
                operation.toString())
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start();
    try {
      var recovery =
          new RedisTokenBudgetRecovery(
              first,
              namespace,
              (reservation, evidence) ->
                  !process.isAlive()
                      && terminal.get()
                      && reservation.operationId().equals(operation),
              (stage, reservation, evidence) -> journal.add(stage));
      Awaitility.await()
          .atMost(DEADLINE)
          .until(
              () -> {
                assertTrue(
                    process.isAlive(), "Reservation owner must remain alive until the crash");
                return recovery.inspect(SCOPE, "0").total() == 1;
              });
      var inventory = recovery.inspect(SCOPE, "0");
      assertEquals(1L, inventory.total());
      var abandoned = inventory.reservations().getFirst();
      assertEquals("crash-test-instance", abandoned.owner().instance());
      assertEquals(operation, abandoned.operationId());
      assertEquals(TokenOperation.LOOKUP, abandoned.operation());
      assertTrue(abandoned.age(inventory.observedAt()).compareTo(Duration.ofMinutes(1)) < 0);
      UUID evidence = UUID.randomUUID();
      assertThrows(
          IllegalStateException.class, () -> recovery.reconcile(SCOPE, abandoned, evidence));
      process.destroyForcibly();
      assertTrue(process.waitFor(5, TimeUnit.SECONDS));
      // Process death alone does not prove that accepted remote effects have ended.
      assertThrows(
          IllegalStateException.class, () -> recovery.reconcile(SCOPE, abandoned, evidence));
      assertTrue(journal.isEmpty());
      var activeBudget =
          new RedisTokenBudget(
              second,
              namespace,
              policy(HIGH_LIMITS, 2),
              (n, id) -> ALARMS.incrementAndGet(),
              new RedisRateLimiter(second.sync(), "", false),
              new TokenBudgetOwner("surviving-instance", UUID.randomUUID()));
      var active = activeBudget.acquire(CALLER, TokenOperation.LOOKUP, SCOPE);
      try {
        terminal.set(true); // Represents the trusted verifier's durable provider reconciliation.
        assertEquals(
            RedisTokenBudgetRecovery.Stage.RELEASED,
            recovery.reconcile(SCOPE, abandoned, evidence));
        assertEquals(
            RedisTokenBudgetRecovery.Stage.ALREADY_RELEASED,
            recovery.reconcile(SCOPE, abandoned, evidence));
        var remaining = recovery.inspect(SCOPE, "0");
        assertEquals(1L, remaining.total());
        assertEquals("surviving-instance", remaining.reservations().getFirst().owner().instance());
        assertEquals(
            List.of(
                RedisTokenBudgetRecovery.Stage.REQUESTED,
                RedisTokenBudgetRecovery.Stage.RELEASED,
                RedisTokenBudgetRecovery.Stage.REQUESTED,
                RedisTokenBudgetRecovery.Stage.ALREADY_RELEASED),
            journal);
      } finally {
        active.close();
      }
      assertEquals(0L, recovery.inspect(SCOPE, "0").total());
    } finally {
      if (process.isAlive()) process.destroyForcibly();
      assertTrue(process.waitFor(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void reconciliationNeverDeletesChangedOrPartialReservationsAndRequiresJournal() {
    for (boolean partial : List.of(false, true)) {
      String namespace = "conflict-budget-" + NAMES.incrementAndGet();
      var budget =
          new RedisTokenBudget(
              first,
              namespace,
              policy(HIGH_LIMITS, 2),
              (n, id) -> ALARMS.incrementAndGet(),
              new RedisRateLimiter(first.sync(), "", false));
      var permit = budget.acquire(CALLER, TokenOperation.LOOKUP, SCOPE);
      try {
        var recovery =
            new RedisTokenBudgetRecovery(second, namespace, (r, e) -> true, (s, r, e) -> {});
        var reservation = recovery.inspect(SCOPE, "0").reservations().getFirst();
        String[] fields = reservation.encoded().split("\\|", -1);
        String lease = reservation.lease().toString();
        String tenantKey = reservation.scope().key() + "tenant:leases";
        var unavailableJournal =
            new RedisTokenBudgetRecovery(
                second,
                namespace,
                (r, e) -> true,
                (stage, r, e) -> {
                  throw new IllegalStateException("Journal unavailable");
                });
        assertThrows(
            IllegalStateException.class,
            () -> unavailableJournal.reconcile(SCOPE, reservation, UUID.randomUUID()));
        assertEquals(reservation.encoded(), first.sync().hget(tenantKey, lease));
        if (partial) first.sync().hdel(fields[5], lease);
        else first.sync().hset(fields[5], lease, "changed-owner");
        assertEquals(
            RedisTokenBudgetRecovery.Stage.CONFLICT,
            recovery.reconcile(SCOPE, reservation, UUID.randomUUID()));
        assertEquals(reservation.encoded(), first.sync().hget(fields[6], lease));
        assertEquals(reservation.encoded(), first.sync().hget(tenantKey, lease));
        if (partial) assertNull(first.sync().hget(fields[5], lease));
        else assertEquals("changed-owner", first.sync().hget(fields[5], lease));
        // Restore only this deliberately corrupted fixture before normal owner cleanup.
        first.sync().hset(fields[5], lease, reservation.encoded());
      } finally {
        permit.close();
      }
    }
  }

  /** Isolated JVM killed by its parent after Redis acknowledges the reservation. */
  public static final class CrashingOwner {
    public static void main(String[] args) throws Exception {
      RedisClient owned = RedisClient.create(args[0]);
      owned.setOptions(
          ClientOptions.builder()
              .timeoutOptions(TimeoutOptions.enabled())
              .requestQueueSize(1000)
              .disconnectedBehavior(DisconnectedBehavior.REJECT_COMMANDS)
              .build());
      StatefulRedisConnection<String, String> connection = owned.connect();
      connection.setTimeout(Duration.ofMillis(500));
      var budget =
          new RedisTokenBudget(
              connection,
              args[1],
              policy(HIGH_LIMITS, 2),
              (n, id) -> {},
              new RedisRateLimiter(connection.sync(), "", false),
              new TokenBudgetOwner("crash-test-instance", UUID.randomUUID()));
      budget.acquire(CALLER, TokenOperation.LOOKUP, SCOPE, UUID.fromString(args[2]));
      new CountDownLatch(1).await();
    }
  }

  @Test
  void queryAndTokenizeShareBudgetsAcrossConnectionsAndIdentityDimensions() {
    Windows two = new Windows(2, 2, 2);
    for (int dimension = 0; dimension < 3; dimension++) {
      Limits limits =
          switch (dimension) {
            case 0 -> new Limits(two, HIGH, HIGH);
            case 1 -> new Limits(HIGH, two, HIGH);
            default -> new Limits(HIGH, HIGH, two);
          };
      String namespace = "test-budget-" + NAMES.incrementAndGet();
      var synchronous =
          new RedisTokenBudget(
              first,
              namespace,
              policy(limits, 100),
              (n, id) -> ALARMS.incrementAndGet(),
              new RedisRateLimiter(first.sync(), "", false));
      var nativeBudget =
          new RedisAsyncTokenBudget(
              second,
              namespace,
              policy(limits, 100),
              (n, id) -> ALARMS.incrementAndGet(),
              new RedisAsyncRateLimiter(second.async(), ""));
      synchronous.acquire(CALLER, TokenOperation.LOOKUP, SCOPE).close();
      StepVerifier.create(
              call(() -> nativeBudget.acquire(CALLER, TokenOperation.TOKENIZE, SCOPE))
                  .flatMap(permit -> Mono.fromCompletionStage(permit.release())))
          .expectComplete()
          .verify(DEADLINE);
      TokenCaller changed =
          switch (dimension) {
            case 0 -> caller("alice", "client-b");
            case 1 -> caller("bob", "client-a");
            default -> caller("bob", "client-b");
          };
      var changedProfile = new VaultScope(SCOPE.type(), SCOPE.tenant(), DOMAIN, "replacement", 2);
      StepVerifier.create(
              call(() -> nativeBudget.acquire(changed, TokenOperation.LOOKUP, changedProfile)))
          .expectErrorSatisfies(
              failure -> {
                var limited = assertInstanceOf(RateLimitedException.class, failure);
                assertEquals(TokenErrors.LIMITED.code(), limited.code());
                assertTrue(
                    limited.retryAfterSeconds() >= 1 && limited.retryAfterSeconds() <= 86400);
              })
          .verify(DEADLINE);
      // Revelation and metadata have their own explicitly configured groups.
      synchronous.acquire(CALLER, TokenOperation.DETOKENIZE, SCOPE).close();
      synchronous.acquire(CALLER, TokenOperation.DESCRIBE, SCOPE).close();
      synchronous.beforeWrite(CALLER, SCOPE);
      StepVerifier.create(call(() -> nativeBudget.beforeWrite(CALLER, SCOPE)))
          .expectError(RateLimitedException.class)
          .verify(DEADLINE);
    }
  }

  @Test
  void concurrencyIsAtomicAcrossReplicasAndHeldUntilOwnerRelease() {
    int alarmsBefore = ALARMS.get();
    String namespace = "test-budget-" + NAMES.incrementAndGet();
    var policy = policy(HIGH_LIMITS, 3);
    var sync =
        new RedisTokenBudget(
            first,
            namespace,
            policy,
            (n, id) -> ALARMS.incrementAndGet(),
            new RedisRateLimiter(first.sync(), "", false));
    var nativeBudget =
        new RedisAsyncTokenBudget(
            second,
            namespace,
            policy,
            (n, id) -> ALARMS.incrementAndGet(),
            new RedisAsyncRateLimiter(second.async(), ""));
    var held = sync.acquire(CALLER, TokenOperation.LOOKUP, SCOPE);
    List<AsyncTokenBudget.Permit> admitted = new ArrayList<>();
    AtomicInteger limited = new AtomicInteger();
    StepVerifier.create(
            Flux.range(0, 64)
                .flatMap(
                    i ->
                        call(() ->
                                nativeBudget.acquire(
                                    CALLER,
                                    i % 2 == 0 ? TokenOperation.LOOKUP : TokenOperation.TOKENIZE,
                                    SCOPE))
                            .onErrorResume(
                                RateLimitedException.class,
                                failure -> {
                                  limited.incrementAndGet();
                                  return Mono.empty();
                                }),
                    64)
                .collectList())
        .assertNext(admitted::addAll)
        .expectComplete()
        .verify(DEADLINE);
    assertEquals(2, admitted.size());
    assertEquals(62, limited.get());
    var recovery = new RedisTokenBudgetRecovery(first, namespace, (r, e) -> false, (s, r, e) -> {});
    var inventory = recovery.inspect(SCOPE, "0");
    assertEquals(3L, inventory.total());
    String keyPattern = inventory.reservations().getFirst().scope().key() + "*:leases";
    for (String key : first.sync().keys(keyPattern)) {
      assertEquals(-1L, first.sync().pttl(key));
      assertEquals(3L, first.sync().hlen(key));
    }
    held.close();
    held.close();
    var replacement = sync.acquire(CALLER, TokenOperation.LOOKUP, SCOPE);
    assertThrows(
        RateLimitedException.class, () -> sync.acquire(CALLER, TokenOperation.LOOKUP, SCOPE));
    replacement.close();
    StepVerifier.create(
            Flux.fromIterable(admitted).concatMap(permit -> call(permit::release)).then())
        .expectComplete()
        .verify(DEADLINE);
    assertEquals(0, first.sync().keys(keyPattern).size());
    assertEquals(alarmsBefore, ALARMS.get());
  }

  @Test
  void actualStoreOutageFailsClosedInBothExecutionModels() {
    String namespace = "test-budget-" + NAMES.incrementAndGet();
    var sync =
        new RedisTokenBudget(
            first,
            namespace,
            policy(HIGH_LIMITS, 3),
            (n, id) -> ALARMS.incrementAndGet(),
            new RedisRateLimiter(first.sync(), "", false));
    var nativeBudget =
        new RedisAsyncTokenBudget(
            second,
            namespace,
            policy(HIGH_LIMITS, 3),
            (n, id) -> ALARMS.incrementAndGet(),
            new RedisAsyncRateLimiter(second.async(), ""));
    valkey.getDockerClient().pauseContainerCmd(valkey.getContainerId()).exec();
    try {
      var failure =
          assertThrows(
              AxizException.class, () -> sync.acquire(CALLER, TokenOperation.LOOKUP, SCOPE));
      assertEquals(TokenErrors.UNAVAILABLE.code(), failure.code());
      assertNull(failure.getCause());
      StepVerifier.create(
              call(() -> nativeBudget.acquire(CALLER, TokenOperation.DETOKENIZE, SCOPE)))
          .expectErrorSatisfies(
              error -> {
                var safe = assertInstanceOf(AxizException.class, error);
                assertEquals(TokenErrors.UNAVAILABLE.code(), safe.code());
                assertNull(safe.getCause());
              })
          .verify(DEADLINE);
      StepVerifier.create(call(() -> nativeBudget.beforeWrite(CALLER, SCOPE)))
          .expectErrorSatisfies(
              error ->
                  assertEquals(
                      TokenErrors.UNAVAILABLE.code(),
                      assertInstanceOf(AxizException.class, error).code()))
          .verify(DEADLINE);
    } finally {
      valkey.getDockerClient().unpauseContainerCmd(valkey.getContainerId()).exec();
    }
  }
}
