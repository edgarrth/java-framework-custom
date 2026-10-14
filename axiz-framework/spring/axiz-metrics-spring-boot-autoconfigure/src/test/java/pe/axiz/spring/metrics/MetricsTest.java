package pe.axiz.spring.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.CoreErrors;
import pe.axiz.core.metrics.Metered;
import pe.axiz.core.metrics.MetricNames;
import reactor.core.publisher.Mono;

class MetricsTest {

  public static class Payments {
    @Metered(event = "payment", category = "payment")
    public String pay(boolean fail) {
      if (fail) {
        throw new BusinessException(CoreErrors.CONFLICT, "duplicated");
      }
      return "ok";
    }

    @Metered(event = "payment.async")
    public Mono<String> payAsync() {
      return Mono.just("ok");
    }
  }

  @Test
  void meteredCountsOutcomesAndReasons() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizMetricsAutoConfiguration.class))
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
        .run(
            ctx -> {
              MeterRegistry registry = ctx.getBean(MeterRegistry.class);
              registry.config().meterFilter(ctx.getBean(MeterFilter.class));
              ProxyFactory pf = new ProxyFactory(new Payments());
              pf.setProxyTargetClass(true);
              pf.addAdvisor(ctx.getBean("axizMeteredAdvisor", Advisor.class));
              Payments p = (Payments) pf.getProxy();
              p.pay(false);
              assertThrows(BusinessException.class, () -> p.pay(true));
              assertEquals("ok", p.payAsync().block());
              assertEquals(
                  1.0,
                  registry
                      .get(MetricNames.COUNT)
                      .tag("event", "payment")
                      .tag("outcome", "success")
                      .counter()
                      .count());
              assertEquals(
                  1.0,
                  registry
                      .get(MetricNames.COUNT)
                      .tag("event", "payment")
                      .tag("outcome", "rejected")
                      .tag("reason", "axz-core-0409")
                      .counter()
                      .count());
              assertEquals(
                  1.0,
                  registry.get(MetricNames.COUNT).tag("event", "payment.async").counter().count());
            });
  }
}
