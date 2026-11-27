package pe.axiz.adapter.tokenization.redis;

import io.lettuce.core.ClientOptions.DisconnectedBehavior;
import io.lettuce.core.api.StatefulRedisConnection;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import pe.axiz.core.crypto.SecureRandoms;
import pe.axiz.core.tokenization.TokenOperation;
import pe.axiz.core.tokenization.vault.TokenBudgetLeaseAlarm;
import pe.axiz.core.tokenization.vault.TokenBudgetPolicy;

/**
 * Scripts de tamaño fijo: concurrencia atómica en tres dimensiones, propiedad explícita y sin
 * expiración.
 */
final class RedisBudgetProtocol {
  static final String ACQUIRE =
      """
      local owned = 0
      for i = 1, 3 do
        local kind = redis.call('TYPE', KEYS[i]).ok
        if kind ~= 'none' and kind ~= 'hash' then return -1 end
        owned = owned + redis.call('HEXISTS', KEYS[i], ARGV[1])
      end
      if owned == 3 then
        for i = 1, 3 do
          local value = redis.call('HGET', KEYS[i], ARGV[1])
          if string.sub(value, 1, string.len(ARGV[5])) ~= ARGV[5] then return -1 end
        end
        return 1
      end
      if owned ~= 0 then return -1 end
      for i = 1, 3 do
        if redis.call('HLEN', KEYS[i]) >= tonumber(ARGV[i+1]) then return 0 end
      end
      local now = redis.call('TIME')
      local stamp = ARGV[5] .. now[1] .. '|' .. now[2]
      for i = 1, 3 do redis.call('HSET', KEYS[i], ARGV[1], stamp) end
      return 1
      """;
  static final String RELEASE =
      """
      for i = 1, 3 do
        local kind = redis.call('TYPE', KEYS[i]).ok
        if kind ~= 'none' and kind ~= 'hash' then return -1 end
      end
      local removed = 0
      for i = 1, 3 do removed = removed + redis.call('HDEL', KEYS[i], ARGV[1]) end
      return removed
      """;
  final TokenBudgetOwner owner;
  final String namespace;
  final TokenBudgetPolicy policy;
  private final TokenBudgetLeaseAlarm alarm;
  private final SecureRandom random = SecureRandoms.drbg("token-budget-leases");

  RedisBudgetProtocol(
      StatefulRedisConnection<String, String> connection,
      String namespace,
      TokenBudgetPolicy policy,
      TokenBudgetLeaseAlarm alarm) {
    this(connection, namespace, policy, alarm, TokenBudgetOwner.process());
  }

  RedisBudgetProtocol(
      StatefulRedisConnection<String, String> connection,
      String namespace,
      TokenBudgetPolicy policy,
      TokenBudgetLeaseAlarm alarm,
      TokenBudgetOwner owner) {
    this.owner = Objects.requireNonNull(owner);
    validateConnection(connection);

    if (namespace == null || !namespace.matches("[a-z][a-z0-9.-]{0,63}"))
      throw new IllegalArgumentException("Invalid token budget namespace");
    this.namespace = namespace;
    this.policy = Objects.requireNonNull(policy);
    this.alarm = Objects.requireNonNull(alarm);
  }

  static void validateConnection(StatefulRedisConnection<String, String> connection) {
    Objects.requireNonNull(connection);
    Duration timeout = connection.getTimeout();
    var options = connection.getOptions();
    if (timeout.compareTo(Duration.ofMillis(1)) < 0
        || timeout.compareTo(Duration.ofSeconds(3)) > 0
        || !options.getTimeoutOptions().isTimeoutCommands()
        || !options.getTimeoutOptions().isApplyConnectionTimeout()
        || options.getRequestQueueSize() < 1
        || options.getRequestQueueSize() > 10_000
        || options.getDisconnectedBehavior() != DisconnectedBehavior.REJECT_COMMANDS)
      throw new IllegalArgumentException(
          "Token budgets require bounded native Redis timeouts and queues");
  }

  UUID newLease() {
    long high = (random.nextLong() & 0xffffffffffff0fffL) | 0x0000000000004000L;
    long low = (random.nextLong() & 0x3fffffffffffffffL) | 0x8000000000000000L;
    return new UUID(high, low);
  }

  String[] arguments(UUID lease, UUID operationId, TokenOperation operation, String[] keys) {
    return new String[] {
      lease.toString(),
      Integer.toString(policy.subjectConcurrent()),
      Integer.toString(policy.clientConcurrent()),
      Integer.toString(policy.tenantConcurrent()),
      "v2|"
          + owner.instance()
          + "|"
          + owner.incarnation()
          + "|"
          + Objects.requireNonNull(operationId)
          + "|"
          + operation.name()
          + "|"
          + keys[0]
          + "|"
          + keys[1]
          + "|"
    };
  }

  void uncertain(UUID lease) {
    try {
      alarm.uncertain(namespace, lease);
    } catch (RuntimeException unavailable) {
      // La admisión o liberación fail-closed sigue fallando; nunca se registra en log una excepción
      // del proveedor ni identidades.
      LoggerFactory.getLogger(RedisBudgetProtocol.class)
          .error("Token budget lease reconciliation alarm unavailable");
    }
  }
}
