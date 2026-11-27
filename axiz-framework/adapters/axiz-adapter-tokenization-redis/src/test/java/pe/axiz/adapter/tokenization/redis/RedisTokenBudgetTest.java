package pe.axiz.adapter.tokenization.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.ClientOptions.DisconnectedBehavior;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.protocol.AsyncCommand;
import io.lettuce.core.protocol.Command;
import io.lettuce.core.protocol.CommandType;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import pe.axiz.adapter.security.redis.RedisAsyncRateLimiter;
import pe.axiz.adapter.security.redis.RedisRateLimiter;
import pe.axiz.core.errors.AxizException;
import pe.axiz.core.errors.RateLimitedException;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenErrors;
import pe.axiz.core.tokenization.TokenOperation;
import pe.axiz.core.tokenization.TokenType;
import pe.axiz.core.tokenization.vault.TokenBudgetPolicy;
import pe.axiz.core.tokenization.vault.TokenBudgetPolicy.Limits;
import pe.axiz.core.tokenization.vault.TokenBudgetPolicy.Windows;
import pe.axiz.core.tokenization.vault.TokenCaller;
import pe.axiz.core.tokenization.vault.VaultScope;

class RedisTokenBudgetTest {
  @Test
  void leasesUseRandomRfc4122IdentifiersAndKeysKeepCallerDimensionsIndependent() {
    var fixture = new Fixture();
    var protocol = new RedisBudgetProtocol(fixture.connection, "vault", POLICY, (n, l) -> {});
    Set<UUID> generated = new HashSet<>();
    for (int i = 0; i < 128; i++) {
      UUID lease = protocol.newLease();
      assertEquals(4, lease.version());
      assertEquals(2, lease.variant());
      assertTrue(generated.add(lease));
    }
    var alice = new BudgetKeys("vault", CALLER, SCOPE).leases();
    var bob = new BudgetKeys("vault", caller("bob", "client-a"), SCOPE).leases();
    var client = new BudgetKeys("vault", caller("alice", "client-b"), SCOPE).leases();
    assertEquals(3, new HashSet<>(alice).size());
    assertNotEquals(alice.getFirst(), bob.getFirst());
    assertNotEquals(alice.get(1), client.get(1));
    assertNotEquals(alice, new BudgetKeys("other-vault", CALLER, SCOPE).leases());
  }

  @Test
  void writeBudgetAndAdmissionBothFailClosedWhenRateLimitsAreExhausted() {
    var fixture = new Fixture();
    var commands = synchronous(fixture);
    when(commands.<List<Long>>eval(
            anyString(), eq(ScriptOutputType.MULTI), any(String[].class), any(String[].class)))
        .thenReturn(List.of(WINDOWS.burst() + 1, 1000L));
    var budget =
        new RedisTokenBudget(
            fixture.connection,
            "vault",
            POLICY,
            (n, l) -> {},
            new RedisRateLimiter(fixture.connection.sync(), "", false));
    assertThrows(
        RateLimitedException.class, () -> budget.acquire(CALLER, TokenOperation.TOKENIZE, SCOPE));
    var async =
        new RedisAsyncTokenBudget(
            fixture.connection,
            "vault",
            POLICY,
            (n, l) -> {},
            new RedisAsyncRateLimiter(fixture.connection.async(), ""));
    assertTrue(async.distributed());
    async.beforeWrite(CALLER, SCOPE).toCompletableFuture().join();
    var denied = RedisTokenBudgetTest.<List<Long>>future();
    denied.complete(List.of(WINDOWS.burst() + 1, 1000L));
    when(fixture.commands.<List<Long>>eval(
            anyString(), eq(ScriptOutputType.MULTI), any(String[].class), any(String[].class)))
        .thenReturn(denied);
    var failure =
        assertThrows(
            CompletionException.class,
            () -> async.beforeWrite(CALLER, SCOPE).toCompletableFuture().join());
    assertInstanceOf(RateLimitedException.class, failure.getCause());
  }

  private static final TokenDomain DOMAIN = new TokenDomain("billing");
  private static final VaultScope SCOPE =
      new VaultScope(new TokenType("DNI"), "tenant", DOMAIN, "dni", 1);
  private static final TokenCaller CALLER = caller("alice", "client-a");
  private static final Windows WINDOWS = new Windows(10, 100, 1000);
  private static final Limits LIMITS = new Limits(WINDOWS, WINDOWS, WINDOWS);
  private static final TokenBudgetPolicy POLICY =
      TokenBudgetPolicy.builder()
          .query(LIMITS)
          .reveal(LIMITS)
          .metadata(LIMITS)
          .writes(LIMITS)
          .subjectConcurrent(2)
          .clientConcurrent(4)
          .tenantConcurrent(10)
          .concurrencyRetryAfter(Duration.ofSeconds(1))
          .build();

  private static TokenCaller caller(String subject, String client) {
    return new TokenCaller(
        subject, client, "tenant", Set.of("SCOPE_tokens:lookup"), Set.of(DOMAIN));
  }

  @Test
  void stableIdentityDimensionsPreventCrossClientAndProfileVersionResets() {
    var keys = new BudgetKeys("vault", CALLER, SCOPE);
    var client = new BudgetKeys("vault", caller("alice", "client-b"), SCOPE);
    var subject = new BudgetKeys("vault", caller("bob", "client-a"), SCOPE);
    var profile =
        new BudgetKeys(
            "vault",
            CALLER,
            new VaultScope(SCOPE.type(), SCOPE.tenant(), DOMAIN, "changed-profile", 2));
    assertEquals(keys.leases().get(0), client.leases().get(0));
    assertEquals(keys.leases().get(1), subject.leases().get(1));
    assertEquals(keys.leases().get(2), subject.leases().get(2));
    assertEquals(keys.leases(), profile.leases());
    assertEquals(
        keys.counters(POLICY, TokenOperation.LOOKUP),
        keys.counters(POLICY, TokenOperation.TOKENIZE));
    assertNotEquals(
        keys.counters(POLICY, TokenOperation.LOOKUP),
        keys.counters(POLICY, TokenOperation.DETOKENIZE));
    assertTrue(
        keys.leases().stream().noneMatch(key -> key.contains("alice") || key.contains("client-a")));
  }

  @Test
  void cancellationBeforeLateGrantReleasesOwnershipWithoutCancellingAcceptedIo() {
    var fixture = new Fixture();
    var grant = RedisTokenBudgetTest.<Long>future();
    var release = RedisTokenBudgetTest.<Long>future();
    when(fixture.commands.<Long>eval(
            eq(RedisBudgetProtocol.ACQUIRE),
            eq(ScriptOutputType.INTEGER),
            any(String[].class),
            any(String[].class)))
        .thenReturn(grant);
    when(fixture.commands.<Long>eval(
            eq(RedisBudgetProtocol.RELEASE),
            eq(ScriptOutputType.INTEGER),
            any(String[].class),
            any(String[].class)))
        .thenReturn(release);
    var result = fixture.budget.acquire(CALLER, TokenOperation.LOOKUP, SCOPE).toCompletableFuture();
    assertTrue(result.cancel(true));
    assertFalse(grant.isCancelled());
    grant.complete(1L);
    verify(fixture.commands)
        .eval(
            eq(RedisBudgetProtocol.RELEASE),
            eq(ScriptOutputType.INTEGER),
            any(String[].class),
            any(String[].class));
    release.complete(3L);
    assertEquals(0, fixture.alarms.get());
    assertTrue(result.isCancelled());
  }

  @Test
  void lostGrantAckAndLostReleaseAckFailClosedWithoutDuplicateScripts() {
    var fixture = new Fixture();
    var grant = RedisTokenBudgetTest.<Long>future();
    var release = RedisTokenBudgetTest.<Long>future();
    when(fixture.commands.<Long>eval(
            eq(RedisBudgetProtocol.ACQUIRE),
            eq(ScriptOutputType.INTEGER),
            any(String[].class),
            any(String[].class)))
        .thenReturn(grant);
    when(fixture.commands.<Long>eval(
            eq(RedisBudgetProtocol.RELEASE),
            eq(ScriptOutputType.INTEGER),
            any(String[].class),
            any(String[].class)))
        .thenReturn(release);
    var admission = fixture.budget.acquire(CALLER, TokenOperation.LOOKUP, SCOPE);
    grant.complete(1L);
    var permit = admission.toCompletableFuture().join();
    var observer = permit.release().toCompletableFuture();
    observer.cancel(true);
    assertFalse(release.isCancelled());
    release.completeExceptionally(
        new RedisCommandTimeoutException("synthetic-secret-provider-message"));
    var failure =
        assertThrows(
            CompletionException.class, () -> permit.release().toCompletableFuture().join());
    var safe = assertInstanceOf(AxizException.class, failure.getCause());
    assertEquals(TokenErrors.UNAVAILABLE.code(), safe.code());
    assertNull(safe.getCause());
    assertEquals(1, fixture.alarms.get());
    verify(fixture.commands, times(1))
        .eval(
            eq(RedisBudgetProtocol.RELEASE),
            eq(ScriptOutputType.INTEGER),
            any(String[].class),
            any(String[].class));

    var lost = RedisTokenBudgetTest.<Long>future();
    when(fixture.commands.<Long>eval(
            eq(RedisBudgetProtocol.ACQUIRE),
            eq(ScriptOutputType.INTEGER),
            any(String[].class),
            any(String[].class)))
        .thenReturn(lost);
    var unknown =
        fixture.budget.acquire(CALLER, TokenOperation.LOOKUP, SCOPE).toCompletableFuture();
    lost.completeExceptionally(new RedisCommandTimeoutException("ack lost"));
    assertThrows(CompletionException.class, unknown::join);
    assertEquals(2, fixture.alarms.get());
    verify(fixture.commands, times(2))
        .eval(
            eq(RedisBudgetProtocol.ACQUIRE),
            eq(ScriptOutputType.INTEGER),
            any(String[].class),
            any(String[].class));
  }

  @Test
  void synchronousPermitsReleaseOnceAndKeepAmbiguousOutcomesClosed() {
    for (Long removed : new Long[] {0L, 3L, 2L, null}) {
      var fixture = new Fixture();
      var commands = synchronous(fixture);
      when(commands.<Long>eval(
              eq(RedisBudgetProtocol.ACQUIRE),
              eq(ScriptOutputType.INTEGER),
              any(String[].class),
              any(String[].class)))
          .thenReturn(1L);
      when(commands.<Long>eval(
              eq(RedisBudgetProtocol.RELEASE),
              eq(ScriptOutputType.INTEGER),
              any(String[].class),
              any(String[].class)))
          .thenReturn(removed);
      var budget =
          new RedisTokenBudget(
              fixture.connection,
              "vault",
              POLICY,
              (namespace, lease) -> fixture.alarms.incrementAndGet(),
              new RedisRateLimiter(fixture.connection.sync(), "", false));
      assertTrue(budget.distributed());
      budget.beforeWrite(CALLER, SCOPE);
      var permit = budget.acquire(CALLER, TokenOperation.DETOKENIZE, SCOPE);
      if (removed != null && (removed == 0 || removed == 3)) {
        permit.close();
        permit.close();
        assertEquals(0, fixture.alarms.get());
      } else {
        var first = assertThrows(AxizException.class, permit::close);
        assertEquals(TokenErrors.UNAVAILABLE.code(), first.code());
        assertEquals(first, assertThrows(AxizException.class, permit::close));
        assertEquals(1, fixture.alarms.get());
      }
      verify(commands, times(1))
          .eval(
              eq(RedisBudgetProtocol.RELEASE),
              eq(ScriptOutputType.INTEGER),
              any(String[].class),
              any(String[].class));
    }
  }

  @Test
  void synchronousAdmissionDistinguishesDenialFromAnUnknownLease() {
    for (Long granted : new Long[] {0L, 2L, null}) {
      var fixture = new Fixture();
      var commands = synchronous(fixture);
      when(commands.<Long>eval(
              eq(RedisBudgetProtocol.ACQUIRE),
              eq(ScriptOutputType.INTEGER),
              any(String[].class),
              any(String[].class)))
          .thenReturn(granted);
      var budget =
          new RedisTokenBudget(
              fixture.connection,
              "vault",
              POLICY,
              (namespace, lease) -> fixture.alarms.incrementAndGet(),
              new RedisRateLimiter(fixture.connection.sync(), "", false));
      var failure =
          assertThrows(
              AxizException.class, () -> budget.acquire(CALLER, TokenOperation.LOOKUP, SCOPE));
      if (granted != null && granted == 0) {
        assertInstanceOf(RateLimitedException.class, failure);
        assertEquals(0, fixture.alarms.get());
      } else {
        assertEquals(TokenErrors.UNAVAILABLE.code(), failure.code());
        assertEquals(1, fixture.alarms.get());
      }
      assertNull(failure.getCause());
    }
  }

  @Test
  void writeBudgetsRejectExhaustionAndDoNotExposeProviderFailures() {
    var fixture = new Fixture();
    var commands = synchronous(fixture);
    var budget =
        new RedisTokenBudget(
            fixture.connection,
            "vault",
            POLICY,
            (namespace, lease) -> {},
            new RedisRateLimiter(fixture.connection.sync(), "", false));
    when(commands.<List<Long>>eval(
            anyString(), eq(ScriptOutputType.MULTI), any(String[].class), any(String[].class)))
        .thenReturn(List.of(WINDOWS.burst() + 1, 1000L));
    assertThrows(RateLimitedException.class, () -> budget.beforeWrite(CALLER, SCOPE));
    when(commands.<List<Long>>eval(
            anyString(), eq(ScriptOutputType.MULTI), any(String[].class), any(String[].class)))
        .thenThrow(new RedisCommandTimeoutException("sensitive-provider-context"));
    var failure = assertThrows(AxizException.class, () -> budget.beforeWrite(CALLER, SCOPE));
    assertEquals(TokenErrors.UNAVAILABLE.code(), failure.code());
    assertNull(failure.getCause());
    assertFalse(failure.toString().contains("sensitive-provider-context"));
  }

  @SuppressWarnings("unchecked")
  private static RedisCommands<String, String> synchronous(Fixture fixture) {
    RedisCommands<String, String> commands = mock(RedisCommands.class);
    when(fixture.connection.sync()).thenReturn(commands);
    when(commands.<List<Long>>eval(
            anyString(), eq(ScriptOutputType.MULTI), any(String[].class), any(String[].class)))
        .thenReturn(List.of(1L, 1000L));
    return commands;
  }

  @Test
  void unboundedOrQueueingConnectionFailsAtBootstrap() {
    var fixture = new Fixture();
    synchronous(fixture);
    when(fixture.connection.getOptions()).thenReturn(ClientOptions.create());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RedisTokenBudget(
                fixture.connection,
                "vault",
                POLICY,
                (namespace, lease) -> {},
                new RedisRateLimiter(fixture.connection.sync(), "", false)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RedisAsyncTokenBudget(
                fixture.connection,
                "vault",
                POLICY,
                (namespace, lease) -> {},
                new RedisAsyncRateLimiter(fixture.connection.async(), "")));
  }

  private static <T> AsyncCommand<String, String, T> future() {
    return new AsyncCommand<>(new Command<>(CommandType.EVAL, null));
  }

  private static final class Fixture {
    @SuppressWarnings("unchecked")
    final StatefulRedisConnection<String, String> connection = mock(StatefulRedisConnection.class);

    @SuppressWarnings("unchecked")
    final RedisAsyncCommands<String, String> commands = mock(RedisAsyncCommands.class);

    final AtomicInteger alarms = new AtomicInteger();
    final RedisAsyncTokenBudget budget;

    Fixture() {
      when(connection.getTimeout()).thenReturn(Duration.ofSeconds(1));
      when(connection.getOptions())
          .thenReturn(
              ClientOptions.builder()
                  .timeoutOptions(TimeoutOptions.enabled())
                  .requestQueueSize(100)
                  .disconnectedBehavior(DisconnectedBehavior.REJECT_COMMANDS)
                  .build());
      when(connection.async()).thenReturn(commands);
      when(commands.<List<Long>>eval(
              anyString(), eq(ScriptOutputType.MULTI), any(String[].class), any(String[].class)))
          .thenAnswer(
              invocation -> {
                var response = RedisTokenBudgetTest.<List<Long>>future();
                response.complete(List.of(1L, 1000L));
                return response;
              });
      budget =
          new RedisAsyncTokenBudget(
              connection,
              "vault",
              POLICY,
              (namespace, lease) -> alarms.incrementAndGet(),
              new RedisAsyncRateLimiter(connection.async(), ""));
    }
  }
}
