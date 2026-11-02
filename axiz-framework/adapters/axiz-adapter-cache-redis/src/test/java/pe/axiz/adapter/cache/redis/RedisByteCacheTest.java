package pe.axiz.adapter.cache.redis;

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
                case "eval" -> {
                  // GET and PTTL of KEYS[1]; the fake keeps every key 1500 ms, "eternal" forever
                  String key = (String) ((Object[]) a[2])[0];
                  byte[] value = data.get(key);
                  java.util.List<Object> reply = new ArrayList<>();
                  reply.add(value);
                  reply.add(value == null ? -2L : key.startsWith("eternal") ? -1L : 1500L);
                  yield reply;
                }
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

  @Test
  void evictionByPrefixWalksEveryPageAndSkipsEmptyOnes() {
    List<List<String>> pages =
        List.of(List.of(), List.of("svc:c:1", "svc:c:2"), List.of("svc:c:3"));
    List<String> unlinked = new ArrayList<>();
    int[] page = {0};
    @SuppressWarnings("unchecked")
    RedisCommands<String, byte[]> paging =
        (RedisCommands<String, byte[]>)
            Proxy.newProxyInstance(
                RedisCommands.class.getClassLoader(),
                new Class<?>[] {RedisCommands.class},
                (p, m, a) ->
                    switch (m.getName()) {
                      case "scan" -> {
                        int i = page[0]++;
                        KeyScanCursor<String> c = new KeyScanCursor<>();
                        c.getKeys().addAll(pages.get(i));
                        c.setCursor(Integer.toString(i + 1));
                        c.setFinished(i == pages.size() - 1);
                        yield c;
                      }
                      case "unlink" -> {
                        for (Object k : (Object[]) a[0]) {
                          unlinked.add((String) k);
                        }
                        yield (long) ((Object[]) a[0]).length;
                      }
                      default -> throw new UnsupportedOperationException(m.getName());
                    });

    assertEquals(3, new RedisByteCache(paging).evictByPrefix("svc:c:"));
    assertEquals(3, page[0], "one SCAN per page until the cursor is finished");
    assertEquals(List.of("svc:c:1", "svc:c:2", "svc:c:3"), unlinked);
  }

  @Test
  void globCharactersOfThePrefixAreEscaped() {
    assertEquals("svc\\[1\\]:\\\\", RedisByteCache.escapeGlob("svc[1]:\\"));
    assertEquals("plain:", RedisByteCache.escapeGlob("plain:"));
  }

  @Test
  void readsTheValueAndItsRemainingTtlInOneScript() {
    Map<String, byte[]> data = new TreeMap<>();
    List<String> commands = new ArrayList<>();
    RedisByteCache cache = new RedisByteCache(fake(data, commands, false));
    assertTrue(cache.getWithTtl("missing").isEmpty());
    cache.put("k", new byte[] {4}, Duration.ofSeconds(2));
    var hit = cache.getWithTtl("k").orElseThrow();
    assertEquals(4, hit.value()[0]);
    assertEquals(Duration.ofMillis(1500), hit.remaining().orElseThrow());
    cache.put("eternal", new byte[] {5}, Duration.ofSeconds(2));
    var eternal = cache.getWithTtl("eternal").orElseThrow();
    assertTrue(eternal.remaining().isEmpty());
    assertTrue(
        eternal.neverExpires(), "PTTL -1 is an entry without expiration, not an unknown one");
    assertTrue(eternal.expiryKnown());
    assertTrue(hit.expiryKnown() && !hit.neverExpires());
    assertEquals(3, commands.stream().filter("eval"::equals).count());
    assertTrue(RedisByteCache.GET_WITH_TTL.contains("PTTL"));
    RedisByteCache down = new RedisByteCache(fake(new TreeMap<>(), new ArrayList<>(), true));
    assertThrows(CacheUnavailableException.class, () -> down.getWithTtl("k"));
  }
}
