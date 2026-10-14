package pe.axiz.core.cache.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisException;
import io.lettuce.core.api.sync.RedisCommands;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import pe.axiz.core.cache.CacheUnavailableException;

class RedisByteCacheTest {

  @SuppressWarnings("unchecked")
  static RedisCommands<String, byte[]> fake(
      Map<String, byte[]> data, List<String> commands, boolean down) {
    return (RedisCommands<String, byte[]>)
        Proxy.newProxyInstance(
            RedisCommands.class.getClassLoader(),
            new Class<?>[] {RedisCommands.class},
            (p, m, a) -> {
              commands.add(m.getName());
              if (down) {
                throw new RedisException("down");
              }
              return switch (m.getName()) {
                case "get" -> data.get((String) a[0]);
                case "set" -> {
                  data.put((String) a[0], (byte[]) a[1]);
                  yield "OK";
                }
                case "del", "unlink" -> {
                  long n = 0;
                  for (Object k : a[0] instanceof Object[] arr ? arr : new Object[] {a[0]}) {
                    n += data.remove((String) k) == null ? 0 : 1;
                  }
                  yield n;
                }
                case "scan" -> {
                  KeyScanCursor<String> c = new KeyScanCursor<>();
                  c.getKeys()
                      .addAll(data.keySet().stream().filter(k -> k.startsWith("svc:c:")).toList());
                  c.setCursor("0");
                  c.setFinished(true);
                  yield c;
                }
                default -> throw new UnsupportedOperationException(m.getName());
              };
            });
  }

  @Test
  void usesSetPxAndScanNeverKeys() {
    Map<String, byte[]> data = new TreeMap<>();
    List<String> commands = new ArrayList<>();
    RedisByteCache cache = new RedisByteCache(fake(data, commands, false));
    cache.put("svc:c:1", new byte[] {1}, Duration.ofMinutes(1));
    cache.put("svc:c:2", new byte[] {2}, Duration.ofMinutes(1));
    cache.put("svc:other", new byte[] {3}, Duration.ofMinutes(1));
    assertEquals(1, cache.get("svc:c:1").orElseThrow()[0]);
    assertEquals(2, cache.evictByPrefix("svc:c:"));
    assertTrue(data.containsKey("svc:other"));
    cache.evict("svc:other");
    assertTrue(data.isEmpty());
    assertTrue(commands.contains("scan"));
    assertTrue(!commands.contains("keys"));
    assertEquals("a\\*b\\?", RedisByteCache.escapeGlob("a*b?"));
  }

  @Test
  void failuresAreCacheUnavailable() {
    RedisByteCache cache = new RedisByteCache(fake(new TreeMap<>(), new ArrayList<>(), true));
    assertThrows(CacheUnavailableException.class, () -> cache.get("k"));
    assertThrows(
        CacheUnavailableException.class, () -> cache.put("k", new byte[0], Duration.ofSeconds(1)));
    assertThrows(CacheUnavailableException.class, () -> cache.evict("k"));
    assertThrows(CacheUnavailableException.class, () -> cache.evictByPrefix("k"));
  }
}
