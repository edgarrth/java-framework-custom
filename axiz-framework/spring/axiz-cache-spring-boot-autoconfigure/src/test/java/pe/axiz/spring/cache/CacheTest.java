package pe.axiz.spring.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.core.cache.AxizCacheable;
import reactor.core.publisher.Mono;

class CacheTest {

  public record Rate(String currency, String value) {}

  public static class Rates {
    final AtomicInteger calls = new AtomicInteger();

    @AxizCacheable("rates")
    public List<Rate> today(String base) {
      calls.incrementAndGet();
      return List.of(new Rate(base, "3.75"));
    }

    @AxizCacheable("rates")
    public Mono<Rate> one(String c) {
      return Mono.fromCallable(
          () -> {
            calls.incrementAndGet();
            return new Rate(c, "1.00");
          });
    }
  }

  @Test
  void genericAndReactiveResultsAreCached() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizCacheAutoConfiguration.class))
        .withPropertyValues("spring.application.name=fx", "axiz.cache.caches.rates.ttl=5m")
        .run(
            ctx -> {
              Rates target = new Rates();
              ProxyFactory pf = new ProxyFactory(target);
              pf.setProxyTargetClass(true);
              pf.addAdvisor(ctx.getBean("axizCacheableAdvisor", Advisor.class));
              Rates rates = (Rates) pf.getProxy();
              assertEquals(List.of(new Rate("USD", "3.75")), rates.today("USD"));
              assertEquals(List.of(new Rate("USD", "3.75")), rates.today("USD"));
              assertEquals(1, target.calls.get());
              assertEquals(new Rate("PEN", "1.00"), rates.one("PEN").block());
              assertEquals(new Rate("PEN", "1.00"), rates.one("PEN").block());
              assertEquals(2, target.calls.get());
              assertEquals(1, ctx.getBean(AxizCaches.class).cache("rates").clear() >= 1 ? 1 : 0);
            });
  }
}
