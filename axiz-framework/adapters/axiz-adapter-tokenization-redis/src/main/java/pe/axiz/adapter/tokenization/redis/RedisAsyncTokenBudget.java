package pe.axiz.adapter.tokenization.redis;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.security.AsyncRateLimiter;
import pe.axiz.core.tokenization.TokenErrors;
import pe.axiz.core.tokenization.TokenOperation;
import pe.axiz.core.tokenization.vault.AsyncTokenBudget;
import pe.axiz.core.tokenization.vault.TokenBudgetLeaseAlarm;
import pe.axiz.core.tokenization.vault.TokenBudgetPolicy;
import pe.axiz.core.tokenization.vault.TokenCaller;
import pe.axiz.core.tokenization.vault.VaultScope;

/** Native admission with an injected fail-closed limiter; cancellation retains accepted I/O. */
public final class RedisAsyncTokenBudget implements AsyncTokenBudget {
  private final RedisAsyncCommands<String, String> redis;
  private final AsyncRateLimiter limiter;
  private final RedisBudgetProtocol protocol;
  private final SerialRedisDispatch dispatch;

  public RedisAsyncTokenBudget(
      StatefulRedisConnection<String, String> connection,
      String namespace,
      TokenBudgetPolicy policy,
      TokenBudgetLeaseAlarm alarm,
      AsyncRateLimiter limiter) {
    protocol = new RedisBudgetProtocol(connection, namespace, policy, alarm);
    dispatch =
        new SerialRedisDispatch(
            connection.getOptions().getRequestQueueSize(), connection.getTimeout());
    redis = connection.async();
    this.limiter = Objects.requireNonNull(limiter);
  }

  @Override
  public CompletionStage<Permit> acquire(
      TokenCaller caller, TokenOperation operation, VaultScope scope) {
    CompletableFuture<Permit> result = new CompletableFuture<>();
    try {
      BudgetKeys keys = new BudgetKeys(protocol.namespace, caller, scope);
      consume(keys.counters(protocol.policy, operation), 0, result, () -> reserve(keys, result));
    } catch (RuntimeException failure) {
      result.completeExceptionally(TokenErrors.failure(TokenErrors.UNAVAILABLE));
    }
    return result;
  }

  @Override
  public CompletionStage<Void> beforeWrite(TokenCaller caller, VaultScope scope) {
    CompletableFuture<Void> result = new CompletableFuture<>();
    try {
      consume(
          new BudgetKeys(protocol.namespace, caller, scope).writes(protocol.policy),
          0,
          result,
          () -> result.complete(null));
    } catch (RuntimeException failure) {
      result.completeExceptionally(TokenErrors.failure(TokenErrors.UNAVAILABLE));
    }
    return result;
  }

  private void consume(
      List<BudgetKeys.Counter> counters,
      int index,
      CompletableFuture<?> result,
      Runnable admitted) {
    if (result.isDone()) return;
    if (index == counters.size()) {
      try {
        admitted.run();
      } catch (RuntimeException failure) {
        result.completeExceptionally(TokenErrors.failure(TokenErrors.UNAVAILABLE));
      }
      return;
    }
    BudgetKeys.Counter counter = counters.get(index);
    try {
      dispatch
          .submit(() -> limiter.tryAcquire(counter.key(), counter.limit(), counter.window(), false))
          .whenComplete(
              (decision, failure) -> {
                if (result.isDone()) return;
                if (failure != null || decision == null) {
                  result.completeExceptionally(TokenErrors.failure(TokenErrors.UNAVAILABLE));
                } else if (!decision.allowed()) {
                  result.completeExceptionally(TokenErrors.limited(decision.retryAfter()));
                } else {
                  consume(counters, index + 1, result, admitted);
                }
              });
    } catch (RuntimeException failure) {
      result.completeExceptionally(TokenErrors.failure(TokenErrors.UNAVAILABLE));
    }
  }

  private void reserve(BudgetKeys keys, CompletableFuture<Permit> result) {
    if (result.isDone()) return;
    UUID lease = protocol.newLease();
    String[] leases = keys.leases().toArray(String[]::new);
    try {
      dispatch
          .submit(
              () ->
                  redis.<Long>eval(
                      RedisBudgetProtocol.ACQUIRE,
                      ScriptOutputType.INTEGER,
                      leases,
                      protocol.arguments(lease)))
          .whenComplete(
              (granted, failure) -> {
                if (failure != null || granted == null || (granted != 0 && granted != 1)) {
                  protocol.uncertain(lease);
                  result.completeExceptionally(TokenErrors.failure(TokenErrors.UNAVAILABLE));
                } else if (granted == 0) {
                  result.completeExceptionally(
                      TokenErrors.limited(protocol.policy.concurrencyRetryAfter()));
                } else {
                  Permit permit = permit(leases, lease);
                  // A grant after cancellation belongs to us until its release is acknowledged.
                  if (!result.complete(permit)) permit.release();
                }
              });
    } catch (RuntimeException failure) {
      protocol.uncertain(lease);
      result.completeExceptionally(TokenErrors.failure(TokenErrors.UNAVAILABLE));
    }
  }

  private Permit permit(String[] leases, UUID lease) {
    AtomicReference<@Nullable CompletableFuture<Void>> shared = new AtomicReference<>();
    return () -> {
      CompletableFuture<Void> existing = shared.get();
      if (existing != null) return existing.copy();
      CompletableFuture<Void> result = new CompletableFuture<>();
      if (!shared.compareAndSet(null, result)) return Objects.requireNonNull(shared.get()).copy();
      try {
        dispatch
            .submit(
                () ->
                    redis.<Long>eval(
                        RedisBudgetProtocol.RELEASE,
                        ScriptOutputType.INTEGER,
                        leases,
                        lease.toString()))
            .whenComplete(
                (removed, failure) -> {
                  if (failure != null || removed == null || (removed != 0 && removed != 3)) {
                    protocol.uncertain(lease);
                    result.completeExceptionally(TokenErrors.failure(TokenErrors.UNAVAILABLE));
                  } else result.complete(null);
                });
      } catch (RuntimeException failure) {
        protocol.uncertain(lease);
        result.completeExceptionally(TokenErrors.failure(TokenErrors.UNAVAILABLE));
      }
      // Cancelling an observer never cancels the accepted release or changes its shared outcome.
      return result.copy();
    };
  }

  @Override
  public boolean distributed() {
    return true;
  }
}
