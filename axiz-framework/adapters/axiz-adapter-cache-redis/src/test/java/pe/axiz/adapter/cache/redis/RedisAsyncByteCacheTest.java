package pe.axiz.adapter.cache.redis;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import pe.axiz.test.support.ImmediateAsyncFakes;

class RedisAsyncByteCacheTest {
  @SuppressWarnings("unchecked")
  static RedisAsyncByteCache cache(RedisCommands<String, byte[]> protocol) {
    return new RedisAsyncByteCache(ImmediateAsyncFakes.adapt(RedisAsyncCommands.class, protocol));
  }

  @Test
  void sameWireProtocolPreservesBytesTtlAndEviction() {
    var data = new TreeMap<String, byte[]>();
    var commands = new ArrayList<String>();
    var cache = cache(RedisByteCacheTest.fake(data, commands, false));
    assertTrue(cache.get("absent").toCompletableFuture().join().isEmpty());
    cache.put("svc:c:1", new byte[] {1}, Duration.ofSeconds(2)).toCompletableFuture().join();
    cache.put("svc:c:2", new byte[] {2}, Duration.ofSeconds(2)).toCompletableFuture().join();
    assertArrayEquals(
        new byte[] {1}, cache.get("svc:c:1").toCompletableFuture().join().orElseThrow());
    assertEquals(
        Duration.ofMillis(1500),
        cache
            .getWithTtl("svc:c:1")
            .toCompletableFuture()
            .join()
            .orElseThrow()
            .remaining()
            .orElseThrow());
    assertTrue(cache.getWithTtl("absent").toCompletableFuture().join().isEmpty());
    cache.put("eternal", new byte[] {3}, Duration.ZERO).toCompletableFuture().join();
    assertTrue(
        cache.getWithTtl("eternal").toCompletableFuture().join().orElseThrow().neverExpires());
    assertEquals(2L, cache.evictByPrefix("svc:c:").toCompletableFuture().join());
    assertTrue(data.containsKey("eternal"));
    cache.evict("eternal").toCompletableFuture().join();
    assertTrue(data.isEmpty());
    assertFalse(commands.contains("keys"));
  }

  @SuppressWarnings("unchecked")
  @Test
  void paginationVisitsEmptyPagesAndDeletesEveryMatchingKey() {
    List<List<String>> pages = List.of(List.of(), List.of("one", "two"), List.of("three"));
    int[] index = {0};
    List<String> deleted = new ArrayList<>();
    RedisCommands<String, byte[]> protocol =
        (RedisCommands<String, byte[]>)
            Proxy.newProxyInstance(
                RedisCommands.class.getClassLoader(),
                new Class<?>[] {RedisCommands.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("scan")) {
                    int current = index[0]++;
                    var page = new KeyScanCursor<String>();
                    page.getKeys().addAll(pages.get(current));
                    page.setCursor(Integer.toString(current + 1));
                    page.setFinished(current == pages.size() - 1);
                    return page;
                  }
                  if (method.getName().equals("unlink")) {
                    for (Object key : (Object[]) args[0]) deleted.add((String) key);
                    return (long) ((Object[]) args[0]).length;
                  }
                  throw new UnsupportedOperationException(method.getName());
                });
    assertEquals(
        3L, cache(protocol).evictByPrefix("prefix*[literal]").toCompletableFuture().join());
    assertEquals(3, index[0]);
    assertEquals(List.of("one", "two", "three"), deleted);
  }

  @SuppressWarnings("unchecked")
  @Test
  void ttlReplyEdgeCasesAreNotMisinterpretedAsUnboundedLifetime() {
    for (List<Object> reply :
        List.<List<Object>>of(
            List.of(),
            List.of("not-bytes"),
            List.of(new byte[] {1}),
            List.of(new byte[] {1}, "not-a-ttl"),
            List.of(new byte[] {1}, -2L),
            List.of(new byte[] {1}, -3L))) {
      RedisCommands<String, byte[]> protocol =
          (RedisCommands<String, byte[]>)
              Proxy.newProxyInstance(
                  RedisCommands.class.getClassLoader(),
                  new Class<?>[] {RedisCommands.class},
                  (proxy, method, args) -> reply);
      var entry = cache(protocol).getWithTtl("key").toCompletableFuture().join();
      if (reply.isEmpty() || !(reply.getFirst() instanceof byte[]) || reply.contains(-2L))
        assertTrue(entry.isEmpty());
      else if (reply.size() == 1 || reply.contains("not-a-ttl"))
        assertFalse(entry.orElseThrow().expiryKnown());
      else assertEquals(Duration.ZERO, entry.orElseThrow().remaining().orElseThrow());
    }
  }

  @SuppressWarnings("unchecked")
  @Test
  void cancellationsReachLettuceAndFailuresStayObservable() {
    class Pending extends CompletableFuture<byte[]> implements RedisFuture<byte[]> {
      @Override
      public String getError() {
        return null;
      }

      @Override
      public boolean await(long timeout, TimeUnit unit) {
        return isDone();
      }
    }
    Pending pending = new Pending();
    var nativeClient =
        (RedisAsyncCommands<String, byte[]>)
            Proxy.newProxyInstance(
                RedisAsyncCommands.class.getClassLoader(),
                new Class<?>[] {RedisAsyncCommands.class},
                (proxy, method, args) -> pending);
    new RedisAsyncByteCache(nativeClient).get("key").toCompletableFuture().cancel(true);
    assertTrue(pending.isCancelled());
    var down = cache(RedisByteCacheTest.fake(new TreeMap<>(), new ArrayList<>(), true));
    assertThrows(CompletionException.class, () -> down.get("key").toCompletableFuture().join());
    assertThrows(
        CompletionException.class, () -> down.getWithTtl("key").toCompletableFuture().join());
    assertThrows(
        CompletionException.class,
        () -> down.put("key", new byte[] {1}, Duration.ofSeconds(1)).toCompletableFuture().join());
    assertThrows(CompletionException.class, () -> down.evict("key").toCompletableFuture().join());
    assertThrows(
        CompletionException.class, () -> down.evictByPrefix("key").toCompletableFuture().join());
  }
}
