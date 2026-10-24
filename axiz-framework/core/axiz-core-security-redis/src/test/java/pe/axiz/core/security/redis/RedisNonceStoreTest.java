package pe.axiz.core.security.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lettuce.core.RedisException;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RedisNonceStoreTest {

  @SuppressWarnings("unchecked")
  private static RedisCommands<String, String> fake(Set<String> keys, boolean down) {
    return (RedisCommands<String, String>)
        Proxy.newProxyInstance(
            RedisCommands.class.getClassLoader(),
            new Class<?>[] {RedisCommands.class},
            (proxy, method, args) -> {
              if (method.getName().equals("set") && args.length == 3) {
                if (down) {
                  throw new RedisException("down");
                }
                assertTrue(args[2] instanceof SetArgs);
                return keys.add((String) args[0]) ? "OK" : null;
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }

  @Test
  void aNonceIsAcceptedOnlyOnceAcrossInstances() {
    Set<String> redis = new HashSet<>();
    RedisNonceStore a = new RedisNonceStore(fake(redis, false), "sig:payments:");
    RedisNonceStore b = new RedisNonceStore(fake(redis, false), "sig:payments:");
    assertTrue(a.markUsed("k1:n1", Duration.ofMinutes(10)));
    assertFalse(b.markUsed("k1:n1", Duration.ofMinutes(10)), "another replica sees it");
    assertEquals(Set.of("sig:payments:k1:n1"), redis);
  }

  @Test
  void anUnavailableRedisFailsClosed() {
    RedisNonceStore store = new RedisNonceStore(fake(new HashSet<>(), true), "sig:");
    assertThrows(IllegalStateException.class, () -> store.markUsed("n", Duration.ofMinutes(1)));
  }
}
