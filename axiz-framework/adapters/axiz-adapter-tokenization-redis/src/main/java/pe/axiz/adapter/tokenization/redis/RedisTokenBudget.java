package pe.axiz.adapter.tokenization.redis;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import pe.axiz.adapter.security.redis.RedisRateLimiter;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.RateLimitedException;
import pe.axiz.core.tokenization.TokenErrors;
import pe.axiz.core.tokenization.TokenOperation;
import pe.axiz.core.tokenization.vault.TokenBudget;
import pe.axiz.core.tokenization.vault.TokenBudgetLeaseAlarm;
import pe.axiz.core.tokenization.vault.TokenBudgetPolicy;
import pe.axiz.core.tokenization.vault.TokenCaller;
import pe.axiz.core.tokenization.vault.VaultScope;

/** Distributed fail-closed budgets. Construct at bootstrap; the caller owns the connection. */
public final class RedisTokenBudget implements TokenBudget {
  private final RedisCommands<String, String> redis;
  private final RedisRateLimiter limiter;
  private final RedisBudgetProtocol protocol;

  public RedisTokenBudget(
      StatefulRedisConnection<String, String> connection,
      String namespace,
      TokenBudgetPolicy policy,
      TokenBudgetLeaseAlarm alarm) {
    protocol = new RedisBudgetProtocol(connection, namespace, policy, alarm);
    redis = connection.sync();
    limiter = new RedisRateLimiter(redis, "", false);
  }

  @Override
  public Permit acquire(TokenCaller caller, TokenOperation operation, VaultScope scope) {
    BudgetKeys keys = new BudgetKeys(protocol.namespace, caller, scope);
    consume(keys.counters(protocol.policy, operation));
    UUID lease = protocol.newLease();
    String[] leases = keys.leases().toArray(String[]::new);
    Long granted;
    try {
      granted =
          redis.eval(
              RedisBudgetProtocol.ACQUIRE,
              ScriptOutputType.INTEGER,
              leases,
              protocol.arguments(lease));
      if (granted == null || (granted != 0 && granted != 1))
        throw TokenErrors.failure(TokenErrors.UNAVAILABLE);
    } catch (RuntimeException failure) {
      protocol.uncertain(lease);
      throw TokenErrors.failure(TokenErrors.UNAVAILABLE);
    }
    if (granted == 0) throw TokenErrors.limited(protocol.policy.concurrencyRetryAfter());
    return new Permit() {
      private boolean closed;
      private @Nullable BusinessException outcome;

      @Override
      public synchronized void close() {
        if (closed) {
          if (outcome != null) throw outcome;
          return;
        }
        closed = true;
        try {
          Long removed =
              redis.eval(
                  RedisBudgetProtocol.RELEASE, ScriptOutputType.INTEGER, leases, lease.toString());
          if (removed == null || (removed != 0 && removed != 3))
            throw TokenErrors.failure(TokenErrors.UNAVAILABLE);
        } catch (RuntimeException failure) {
          protocol.uncertain(lease);
          outcome = TokenErrors.failure(TokenErrors.UNAVAILABLE);
          throw outcome;
        }
      }
    };
  }

  @Override
  public void beforeWrite(TokenCaller caller, VaultScope scope) {
    consume(new BudgetKeys(protocol.namespace, caller, scope).writes(protocol.policy));
  }

  private void consume(List<BudgetKeys.Counter> counters) {
    try {
      for (BudgetKeys.Counter counter : counters) {
        var decision = limiter.tryAcquire(counter.key(), counter.limit(), counter.window(), false);
        if (!decision.allowed()) throw TokenErrors.limited(decision.retryAfter());
      }
    } catch (RateLimitedException denied) {
      throw denied;
    } catch (RuntimeException failure) {
      throw TokenErrors.failure(TokenErrors.UNAVAILABLE);
    }
  }

  @Override
  public boolean distributed() {
    return true;
  }
}
