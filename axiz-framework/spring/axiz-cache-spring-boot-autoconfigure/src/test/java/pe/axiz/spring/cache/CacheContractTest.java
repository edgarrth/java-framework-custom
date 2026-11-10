package pe.axiz.spring.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import pe.axiz.core.cache.AxizCacheable;
import pe.axiz.core.cache.ByteCache;
import pe.axiz.core.cache.CacheKeys;
import pe.axiz.core.cache.CacheScope;
import pe.axiz.core.cache.InMemoryByteCache;
import pe.axiz.core.cache.TwoLevelCache;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the cache guarantees to the methods it wraps: separate keys, explicit sharing, freshness.
 */
class CacheContractTest {

  public static class Lookup {
    final AtomicInteger calls = new AtomicInteger();

    @AxizCacheable("lookup")
    public String find(String value) {
      calls.incrementAndGet();
      return "string:" + value;
    }

    @AxizCacheable("lookup")
    public String find(Integer value) {
      calls.incrementAndGet();
      return "integer:" + value;
    }

    @AxizCacheable("lookup")
    public String other(String value) {
      calls.incrementAndGet();
      return "other:" + value;
    }

    @AxizCacheable(value = "lookup", keyName = "rate")
    public String rate(String currency) {
      calls.incrementAndGet();
      return "rate:" + currency;
    }

    @AxizCacheable(value = "lookup", keyName = "rate")
    public String sameRate(String currency) {
      calls.incrementAndGet();
      return "same:" + currency;
    }

    @AxizCacheable("lookup")
    public String byObject(Object anything) {
      calls.incrementAndGet();
      return "object";
    }

    @AxizCacheable("lookup")
    public String nothing(String value) {
      calls.incrementAndGet();
      return null;
    }

    @AxizCacheable(value = "lookup", ttl = "PT1S")
    public Flux<String> many(String value) {
      return Flux.defer(
          () -> {
            calls.incrementAndGet();
            return Flux.just(value, value + value);
          });
    }

    @AxizCacheable("lookup")
    public Mono<String> broken(String value) {
      calls.incrementAndGet();
      return null;
    }

    public String plain(String value) {
      calls.incrementAndGet();
      return value;
    }
  }

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizCacheAutoConfiguration.class))
          .withPropertyValues("spring.application.name=svc");

  private static Lookup proxy(ApplicationContext ctx, Lookup target) {
    ProxyFactory pf = new ProxyFactory(target);
    pf.setProxyTargetClass(true);
    pf.addAdvisor(ctx.getBean("axizCacheableAdvisor", Advisor.class));
    return (Lookup) pf.getProxy();
  }

  @Test
  void overloadsAndEquallyNamedArgumentsNeverShareEntries() {
    runner.run(
        ctx -> {
          Lookup target = new Lookup();
          Lookup lookup = proxy(ctx, target);
          assertEquals("string:7", lookup.find("7"));
          assertEquals("integer:7", lookup.find(7));
          assertEquals("other:7", lookup.other("7"));
          assertEquals("string:7", lookup.find("7"));
          assertEquals("integer:7", lookup.find(7));
          assertEquals(3, target.calls.get());
        });
  }

  @Test
  void methodsShareEntriesOnlyThroughAnExplicitKeyName() {
    runner.run(
        ctx -> {
          Lookup target = new Lookup();
          Lookup lookup = proxy(ctx, target);
          assertEquals("rate:PEN", lookup.rate("PEN"));
          assertEquals("rate:PEN", lookup.sameRate("PEN"), "same namespace, same arguments");
          assertEquals(1, target.calls.get());
        });
  }

  @Test
  void argumentsWithoutAStableKeyAreRejected() {
    runner.run(
        ctx -> {
          Lookup lookup = proxy(ctx, new Lookup());
          assertEquals("object", lookup.byObject(List.of("ok")));
          IllegalArgumentException e =
              assertThrows(IllegalArgumentException.class, () -> lookup.byObject(new Object()));
          assertTrue(e.getMessage().contains("java.lang.Object"), e.getMessage());
        });
  }

  @Test
  void nullResultsAndUnannotatedMethodsAreNotCached() {
    runner.run(
        ctx -> {
          Lookup target = new Lookup();
          Lookup lookup = proxy(ctx, target);
          assertNull(lookup.nothing("x"));
          assertNull(lookup.nothing("x"));
          assertEquals("p", lookup.plain("p"));
          assertEquals("p", lookup.plain("p"));
          assertEquals(4, target.calls.get());
        });
  }

  @Test
  void fluxResultsAreCachedAsAListAndAReactiveNullIsEmpty() {
    runner.run(
        ctx -> {
          Lookup target = new Lookup();
          Lookup lookup = proxy(ctx, target);
          assertEquals(List.of("a", "aa"), lookup.many("a").collectList().block());
          assertEquals(List.of("a", "aa"), lookup.many("a").collectList().block());
          assertEquals(1, target.calls.get());
          assertNull(lookup.broken("x").block());
        });
  }

  @Test
  void unreadableEntriesAreEvictedAndReloaded() {
    runner.run(
        ctx -> {
          Lookup target = new Lookup();
          Lookup lookup = proxy(ctx, target);
          AxizCaches caches = ctx.getBean(AxizCaches.class);
          lookup.find("9");
          String key =
              caches.prefix()
                  + ":lookup:"
                  + CacheKeys.FORMAT
                  + ":"
                  + AxizCacheableInterceptor.signature(Lookup.class.getMethod("find", String.class))
                  + ":"
                  + CacheScopes.part(CacheScope.TENANT, null, null)
                  + ":s=9";
          assertTrue(caches.store().get(key).isPresent(), key);
          caches
              .store()
              .put(key, "not json".getBytes(StandardCharsets.UTF_8), Duration.ofMinutes(1));
          assertEquals("string:9", lookup.find("9"));
          assertEquals(2, target.calls.get());
        });
  }

  @Test
  void theSignatureNamesClassMethodAndParameterTypes() throws Exception {
    assertEquals(
        Lookup.class.getName() + "#find(java.lang.Integer)",
        AxizCacheableInterceptor.signature(Lookup.class.getMethod("find", Integer.class)));
    assertNotEquals(
        AxizCacheableInterceptor.signature(Lookup.class.getMethod("find", String.class)),
        AxizCacheableInterceptor.signature(Lookup.class.getMethod("find", Integer.class)));
  }

  @Test
  void levelsFollowTheConfiguration() {
    ByteCache shared = new InMemoryByteCache(10, Clock.systemUTC());
    runner
        .withConfiguration(AutoConfigurations.of(AxizCacheCaffeineAutoConfiguration.class))
        .withBean("axizL2Cache", ByteCache.class, () -> shared)
        .withPropertyValues("axiz.cache.prefix=pay", "axiz.cache.caches.fx.ttl=30s")
        .run(
            ctx -> {
              AxizCaches caches = ctx.getBean(AxizCaches.class);
              assertThat(caches.store()).isInstanceOf(TwoLevelCache.class);
              assertEquals("pay", caches.prefix());
              assertEquals(Duration.ofSeconds(30), caches.ttl("fx"));
              assertEquals(Duration.ofMinutes(10), caches.ttl("other"));
              assertThat(ctx.getBean("axizL1Cache")).isInstanceOf(CaffeineByteCache.class);
            });
    runner
        .withBean("axizL2Cache", ByteCache.class, () -> shared)
        .withPropertyValues("axiz.cache.redis=false")
        .run(
            ctx ->
                assertThat(ctx.getBean(AxizCaches.class).store())
                    .isInstanceOf(InMemoryByteCache.class));
    runner
        .withPropertyValues("axiz.cache.enabled=false")
        .run(ctx -> assertThat(ctx).doesNotHaveBean(AxizCaches.class));
  }

  @Test
  void caffeineKeepsCopiesAndEvictsByPrefix() {
    CaffeineByteCache cache = new CaffeineByteCache(10);
    byte[] value = {1};
    cache.put("svc:a:1", value, Duration.ofMinutes(1));
    value[0] = 9;
    byte[] read = cache.get("svc:a:1").orElseThrow();
    assertEquals(1, read[0]);
    read[0] = 8;
    assertEquals(1, cache.get("svc:a:1").orElseThrow()[0]);
    cache.put("svc:a:1", new byte[] {2}, Duration.ofMinutes(1));
    cache.put("svc:a:2", new byte[] {3}, Duration.ofMinutes(1));
    cache.put("svc:b:1", new byte[] {4}, Duration.ofMinutes(1));
    assertEquals(2, cache.evictByPrefix("svc:a:"));
    cache.evict("svc:b:1");
    assertTrue(cache.get("svc:b:1").isEmpty());
    cache.put("short", new byte[] {5}, Duration.ofNanos(1));
    assertTrue(cache.get("short").isEmpty(), "expired");
  }

  @Test
  void theCodecRoundTripsValues() {
    JacksonValueCodec codec = new JacksonValueCodec(JsonMapper.builder().build());
    byte[] bytes = codec.encode(Map.of("a", 1));
    assertEquals(Map.of("a", 1), codec.decode(bytes, Map.class));
    assertThat(codec.mapper()).isNotNull();
  }
}
