package pe.axiz.adapter.idempotency.redis;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lettuce.core.SetArgs;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.protocol.CommandArgs;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import pe.axiz.core.idempotency.IdempotencyRecord;
import pe.axiz.core.idempotency.IdempotencyStore;
import pe.axiz.core.idempotency.StoredResponse;
import pe.axiz.core.idempotency.StoredResponseCodec;
import pe.axiz.test.contracts.AsyncIdempotencyProbe;
import pe.axiz.test.contracts.IdempotencyStoreContract;
import pe.axiz.test.support.ImmediateAsyncFakes;
import pe.axiz.test.support.MutableClock;

class RedisIdempotencyStoreTest {

  /**
   * Minimal Redis: SET NX/XX, GET, DEL and the two Lua scripts of the store, evaluated with the
   * same semantics (status and owner checked atomically).
   */
  @SuppressWarnings("unchecked")
  static RedisCommands<String, String> fakeRedis(Map<String, String> data) {
    return (RedisCommands<String, String>)
        Proxy.newProxyInstance(
            RedisCommands.class.getClassLoader(),
            new Class<?>[] {RedisCommands.class},
            (proxy, method, args) -> {
              synchronized (data) {
                return switch (method.getName()) {
                  case "set" -> {
                    String k = (String) args[0];
                    String options = args.length > 2 ? options((SetArgs) args[2]) : "";
                    boolean nx = options.contains("NX");
                    boolean xx = options.contains("XX");
                    if ((nx && data.containsKey(k)) || (xx && !data.containsKey(k))) {
                      yield null;
                    }
                    data.put(k, (String) args[1]);
                    yield "OK";
                  }
                  case "get" -> data.get((String) args[0]);
                  case "del" -> {
                    long n = 0;
                    for (Object k : (Object[]) args[0]) {
                      n += data.remove((String) k) == null ? 0 : 1;
                    }
                    yield n;
                  }
                  case "eval" -> {
                    String script = (String) args[0];
                    String key = String.valueOf(((Object[]) args[2])[0]);
                    Object[] argv = (Object[]) args[3];
                    String value = data.get(key);
                    if (value == null) {
                      yield 0L;
                    }
                    if (script.equals(RedisIdempotencyStore.CAS_SCRIPT)) {
                      if (!value.equals(argv[0])) yield 0L;
                      String replacement = String.valueOf(argv[1]);
                      if (replacement.isEmpty()) data.remove(key);
                      else data.put(key, replacement);
                      yield 1L;
                    }
                    IdempotencyRecord r = StoredResponseCodec.read(key, value);
                    if (r.status() != IdempotencyRecord.Status.IN_PROGRESS
                        || !argv[0].equals(r.owner())) {
                      yield 0L;
                    }
                    if (script.equals(RedisIdempotencyStore.COMPLETE_SCRIPT)) {
                      data.put(key, String.valueOf(argv[1]));
                    } else if (script.equals(RedisIdempotencyStore.RELEASE_SCRIPT)) {
                      data.remove(key);
                    } else {
                      throw new UnsupportedOperationException(script);
                    }
                    yield 1L;
                  }
                  default -> throw new UnsupportedOperationException(method.getName());
                };
              }
            });
  }

  /** Options as sent to the server, for example {@code NX PX 60000}. */
  static String options(SetArgs setArgs) {
    CommandArgs<String, String> args = new CommandArgs<>(StringCodec.UTF8);
    setArgs.build(args);
    return args.toCommandString().toUpperCase(Locale.ROOT);
  }

  private static IdempotencyRecord inProgress(String key, String owner, Instant exp) {
    return new IdempotencyRecord(key, "fp", IdempotencyRecord.Status.IN_PROGRESS, owner, null, exp);
  }

  @Test
  void startCompleteRelease() {
    Map<String, String> data = new HashMap<>();
    RedisIdempotencyStore store = new RedisIdempotencyStore(fakeRedis(data), "idem:");
    Instant exp = Instant.now().plusSeconds(60);
    assertTrue(store.tryStart(inProgress("k1", "a", exp)));
    assertFalse(store.tryStart(inProgress("k1", "b", exp)));
    assertTrue(data.containsKey("idem:k1"));
    assertTrue(store.release("k1", "a"));
    assertFalse(data.containsKey("idem:k1"));
    assertTrue(store.tryStart(inProgress("k1", "a", exp)));
    assertTrue(store.complete("k1", "a", new StoredResponse(201, Map.of(), new byte[0]), exp));
    assertEquals(
        IdempotencyRecord.Status.COMPLETED, store.find("k1", Instant.now()).orElseThrow().status());
    assertFalse(store.release("k1", "a"));
    assertTrue(data.containsKey("idem:k1"), "completed records are kept");
    assertFalse(
        store.complete("missing", "a", new StoredResponse(200, Map.of(), new byte[0]), exp));
    assertTrue(store.find("missing", Instant.now()).isEmpty());
  }

  /** Regression: A's lock expires, B takes the key, A finishes late. */
  @Test
  void aLateOwnerCannotOverwriteOrReleaseTheKeyOfTheNewOwner() {
    Map<String, String> data = new HashMap<>();
    RedisIdempotencyStore store = new RedisIdempotencyStore(fakeRedis(data), "idem:");
    Instant exp = Instant.now().plusSeconds(60);
    assertTrue(store.tryStart(inProgress("k", "owner-a", exp)));
    data.remove("idem:k"); // A's lock expired (Redis TTL)
    assertTrue(store.tryStart(inProgress("k", "owner-b", exp)));

    assertFalse(
        store.complete("k", "owner-a", new StoredResponse(201, Map.of(), new byte[] {'A'}), exp));
    assertFalse(store.release("k", "owner-a"));
    IdempotencyRecord current = store.find("k", Instant.now()).orElseThrow();
    assertEquals(IdempotencyRecord.Status.IN_PROGRESS, current.status());
    assertEquals("owner-b", current.owner());

    assertTrue(
        store.complete("k", "owner-b", new StoredResponse(201, Map.of(), new byte[] {'B'}), exp));
    assertArrayEquals(
        new byte[] {'B'}, store.find("k", Instant.now()).orElseThrow().response().body());
  }

  @Test
  void startUsesSetIfAbsentWithTheLockTtl() {
    String options = options(SetArgs.Builder.nx().px(1500));
    assertTrue(options.contains("NX") && options.contains("PX 1500"), options);
  }

  @Test
  void scriptsCheckStatusAndOwnerAtomically() {
    for (String script :
        new String[] {
          RedisIdempotencyStore.COMPLETE_SCRIPT, RedisIdempotencyStore.RELEASE_SCRIPT
        }) {
      assertTrue(script.contains("r['status'] ~= 'IN_PROGRESS'"));
      assertTrue(script.contains("r['owner'] ~= ARGV[1]"));
    }
    assertTrue(RedisIdempotencyStore.COMPLETE_SCRIPT.contains("'PX', ARGV[3]"));
  }

  /** Shared store contract against the fake; the fake has no TTL, so expiry deletes the key. */
  @TestFactory
  Stream<DynamicTest> contract() {
    Map<String, String> data = new HashMap<>();
    IdempotencyStoreContract.Backend backend =
        new IdempotencyStoreContract.Backend() {
          @Override
          public IdempotencyStore create(MutableClock clock) {
            data.clear();
            return new RedisIdempotencyStore(fakeRedis(data), "idem:", clock);
          }

          @Override
          public void expire(IdempotencyStore store, String key, MutableClock clock) {
            data.remove("idem:" + key);
            clock.advance(IdempotencyStoreContract.RETENTION.plusSeconds(1));
          }
        };
    return IdempotencyStoreContract.checks(backend).stream()
        .map(c -> DynamicTest.dynamicTest(c.name(), c::run));
  }

  @TestFactory
  @SuppressWarnings("unchecked")
  Stream<DynamicTest> nativeAsyncContract() {
    Map<String, String> data = new HashMap<>();
    IdempotencyStoreContract.Backend backend =
        new IdempotencyStoreContract.Backend() {
          @Override
          public IdempotencyStore create(MutableClock clock) {
            data.clear();
            return new AsyncIdempotencyProbe(
                new RedisAsyncIdempotencyStore(
                    ImmediateAsyncFakes.adapt(RedisAsyncCommands.class, fakeRedis(data)),
                    "async:",
                    clock));
          }

          @Override
          public void expire(IdempotencyStore store, String key, MutableClock clock) {
            data.remove("async:" + key);
            clock.advance(IdempotencyStoreContract.RETENTION.plusSeconds(1));
          }
        };
    return IdempotencyStoreContract.checks(backend).stream()
        .map(check -> DynamicTest.dynamicTest("async: " + check.name(), check::run));
  }
}
