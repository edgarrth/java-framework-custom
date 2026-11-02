package pe.axiz.adapter.lock.redis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lettuce.core.api.sync.RedisCommands;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RedisDistributedLockTest {

  @SuppressWarnings("unchecked")
  static RedisCommands<String, String> fake(Map<String, String> data) {
    return (RedisCommands<String, String>)
        Proxy.newProxyInstance(
            RedisCommands.class.getClassLoader(),
            new Class<?>[] {RedisCommands.class},
            (p, m, a) ->
                switch (m.getName()) {
                  case "set" ->
                      data.putIfAbsent((String) a[0], (String) a[1]) == null ? "OK" : null;
                  case "eval" -> {
                    String key = ((String[]) a[2])[0];
                    String token = (String) ((Object[]) a[3])[0];
                    boolean owner = token.equals(data.get(key));
                    if (owner && a[0].equals(RedisDistributedLock.RELEASE)) {
                      data.remove(key);
                    }
                    yield owner ? 1L : 0L;
                  }
                  default -> throw new UnsupportedOperationException(m.getName());
                });
  }

  @Test
  void onlyTheOwnerRenewsAndReleases() {
    Map<String, String> data = new HashMap<>();
    RedisDistributedLock lock = new RedisDistributedLock(fake(data), "lock:");
    var a = lock.tryAcquire("account:1", Duration.ofSeconds(5)).orElseThrow();
    assertTrue(lock.tryAcquire("account:1", Duration.ofSeconds(5)).isEmpty());
    assertTrue(a.renew(Duration.ofSeconds(5)));
    data.put("lock:account:1", "someone-else");
    assertFalse(a.renew(Duration.ofSeconds(5)));
    a.release();
    assertTrue(data.containsKey("lock:account:1"));
    data.clear();
    var b = lock.tryAcquire("account:1", Duration.ofSeconds(5)).orElseThrow();
    b.close();
    assertTrue(data.isEmpty());
  }

  @Test
  @SuppressWarnings("unchecked")
  void nativeAsyncLeaseIsFencedAndRejectsInvalidDurations() {
    Map<String, String> data = new HashMap<>();
    var lock =
        new RedisAsyncDistributedLock(
            pe.axiz.test.support.ImmediateAsyncFakes.adapt(
                io.lettuce.core.api.async.RedisAsyncCommands.class, fake(data)),
            "lock:");
    var owner =
        lock.tryAcquire("account:1", Duration.ofSeconds(1))
            .toCompletableFuture()
            .join()
            .orElseThrow();
    org.junit.jupiter.api.Assertions.assertEquals("account:1", owner.resource());
    assertFalse(owner.token().isBlank());
    assertTrue(
        lock.tryAcquire("account:1", Duration.ofSeconds(1)).toCompletableFuture().join().isEmpty());
    assertTrue(owner.renew(Duration.ofSeconds(2)).toCompletableFuture().join());
    data.put("lock:account:1", "new-owner");
    assertFalse(owner.renew(Duration.ofSeconds(2)).toCompletableFuture().join());
    owner.release().toCompletableFuture().join();
    assertTrue(data.containsKey("lock:account:1"));
    data.clear();
    var next =
        lock.tryAcquire("account:1", Duration.ofSeconds(1))
            .toCompletableFuture()
            .join()
            .orElseThrow();
    next.release().toCompletableFuture().join();
    assertTrue(data.isEmpty());
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> lock.tryAcquire("x", Duration.ZERO));
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> next.renew(Duration.ofSeconds(-1)));
  }
}
