package pe.axiz.adapter.security.redis;

import io.lettuce.core.RedisException;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.Objects;
import pe.axiz.core.security.NonceStore;

/**
 * Almacén de nonces compartido por todas las instancias de un servicio: un {@code SET key 1 NX PX
 * ttl} atómico por nonce. Si Redis no puede responder, lanza una excepción, de modo que la
 * verificación de la firma falla en cerrado.
 */
public final class RedisNonceStore implements NonceStore {

  private final RedisCommands<String, String> redis;
  private final String prefix;

  public RedisNonceStore(RedisCommands<String, String> redis, String prefix) {
    this.redis = Objects.requireNonNull(redis);
    this.prefix = Objects.requireNonNull(prefix);
  }

  @Override
  public boolean markUsed(String nonce, Duration ttl) {
    try {
      return "OK".equals(redis.set(prefix + nonce, "1", SetArgs.Builder.nx().px(ttl.toMillis())));
    } catch (RedisException e) {
      throw new IllegalStateException("Nonce store unavailable", e);
    }
  }
}
