package pe.axiz.spring.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import pe.axiz.core.errors.AxizException;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.CoreErrors;
import pe.axiz.core.errors.ExternalErrorDetail;
import pe.axiz.core.errors.ExternalServiceException;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.metrics.FailureClassifier;
import pe.axiz.core.metrics.Metered;
import pe.axiz.core.metrics.MetricEvent;
import pe.axiz.core.metrics.MetricNames;
import pe.axiz.core.metrics.MetricSource;
import pe.axiz.core.metrics.MetricsSettings;
import pe.axiz.core.metrics.Outcome;
import pe.axiz.core.money.Money;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** What {@code @Metered} records for each outcome, on both programming models. */
class MeteredBehaviourTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizMetricsAutoConfiguration.class))
          .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

  /** An argument that tells the metrics its channel and amount. */
  record Transfer(String channel, Money amount) implements MetricSource {
    @Override
    public void describe(MetricEvent event) {
      event.channel(channel).amount(amount);
    }
  }

  /** An argument whose description fails: the call must not be affected. */
  record Broken() implements MetricSource {
    @Override
    public void describe(MetricEvent event) {
      throw new IllegalStateException("bad description");
    }
  }

  public static class Payments {
    @Metered(event = "transfer", category = "payment")
    public Mono<String> transfer(Transfer t) {
      return Mono.just("ok");
    }

    @Metered(event = "transfer.rejected")
    public Mono<String> rejected() {
      return Mono.error(new BusinessException(CoreErrors.CONFLICT, "duplicated"));
    }

    @Metered(event = "statement", duration = false)
    public Flux<Integer> statement(boolean fail) {
      return fail
          ? Flux.concat(Flux.just(1), Flux.error(new IllegalStateException("db down")))
          : Flux.just(1, 2, 3);
    }

    @Metered(event = "waiting")
    public Mono<String> waiting() {
      return Mono.never();
    }

    @Metered(event = "described")
    public String described(Broken b) {
      return "ok";
    }

    public String plain() {
      return "plain";
    }
  }

  private static Payments proxy(Advisor advisor) {
    ProxyFactory pf = new ProxyFactory(new Payments());
    pf.setProxyTargetClass(true);
    pf.addAdvisor(advisor);
    return (Payments) pf.getProxy();
  }

  private static double count(MeterRegistry registry, String event, String outcome) {
    return registry
        .get(MetricNames.COUNT)
        .tag("event", event)
        .tag("outcome", outcome)
        .counter()
        .count();
  }

  @Test
  void reactiveResultsAreMeasuredWhenTheyFinish() {
    runner.run(
        ctx -> {
          MeterRegistry registry = ctx.getBean(MeterRegistry.class);
          registry.config().meterFilter(ctx.getBean(MeterFilter.class));
          Payments p = proxy(ctx.getBean("axizMeteredAdvisor", Advisor.class));

          Mono<String> pending = p.transfer(new Transfer("mobile", Money.of("150.00", "PEN")));
          assertThat(registry.find(MetricNames.COUNT).tag("event", "transfer").counter()).isNull();
          assertEquals("ok", pending.block());
          assertEquals(1.0, count(registry, "transfer", "success"));
          assertEquals(
              1.0,
              registry
                  .get(MetricNames.COUNT)
                  .tag("event", "transfer")
                  .tag("channel", "mobile")
                  .counter()
                  .count());
          assertEquals(
              150.0,
              registry.get(MetricNames.AMOUNT).tag("event", "transfer").summary().totalAmount());
          assertTrue(
              registry.get(MetricNames.DURATION).tag("event", "transfer").timer().count() == 1);

          assertThrows(BusinessException.class, () -> p.rejected().block());
          assertEquals(
              1.0,
              registry
                  .get(MetricNames.COUNT)
                  .tag("event", "transfer.rejected")
                  .tag("outcome", "rejected")
                  .tag("reason", "axz-core-0409")
                  .counter()
                  .count());

          assertEquals(3, p.statement(false).collectList().block().size());
          assertEquals(1.0, count(registry, "statement", "success"));
          assertThrows(IllegalStateException.class, () -> p.statement(true).blockLast());
          assertEquals(1.0, count(registry, "statement", "error"));
          assertThat(registry.find(MetricNames.DURATION).tag("event", "statement").timer())
              .isNull();

          Disposable subscription = p.waiting().subscribe();
          subscription.dispose();
          assertEquals(1.0, count(registry, "waiting", "cancelled"));
        });
  }

  @Test
  void aFailingDescriptionNeverBreaksTheCallAndUnannotatedMethodsPassThrough() {
    runner.run(
        ctx -> {
          MeterRegistry registry = ctx.getBean(MeterRegistry.class);
          Payments p = proxy(ctx.getBean("axizMeteredAdvisor", Advisor.class));
          assertEquals("ok", p.described(new Broken()));
          assertEquals(1.0, count(registry, "described", "success"));

          ProxyFactory direct = new ProxyFactory(new Payments());
          direct.setProxyTargetClass(true);
          direct.addAdvice(new MeteredInterceptor(() -> ctx.getBean(EventMetrics.class)));
          assertEquals("plain", ((Payments) direct.getProxy()).plain());
          assertThat(registry.find(MetricNames.COUNT).tag("event", "plain").counter()).isNull();
        });
  }

  @Test
  void axizErrorsAreClassifiedByTypeWhereverTheyAreInTheCauseChain() {
    AxizFailureClassifier classifier = new AxizFailureClassifier();
    FailureClassifier.Classification business =
        classifier
            .classify(new RuntimeException(new BusinessException(CoreErrors.CONFLICT, "dup")))
            .orElseThrow();
    assertEquals(Outcome.REJECTED, business.outcome());
    assertEquals("axz-core-0409", business.reason());

    FailureClassifier.Classification technical =
        classifier.classify(new AxizException(CoreErrors.UNEXPECTED, "boom")).orElseThrow();
    assertEquals(Outcome.ERROR, technical.outcome());

    FailureClassifier.Classification external =
        classifier
            .classify(
                ExternalServiceException.from(
                    new ExternalErrorDetail("core-banking", 503, null, null, "")))
            .orElseThrow();
    assertEquals(Outcome.ERROR, external.outcome());

    assertTrue(classifier.classify(new IllegalStateException("not ours")).isEmpty());
  }

  @Test
  void declaredTagsEventsAndBucketsComeFromConfiguration() {
    runner
        .withPropertyValues(
            "axiz.metrics.max-events=10",
            "axiz.metrics.tags.segment.allowed=retail,corporate",
            "axiz.metrics.standard-tags.product.max=5",
            "axiz.metrics.events.transfer.category=payment",
            "axiz.metrics.events.transfer.value-unit=items",
            "axiz.metrics.events.transfer.duration-slos=100ms,1s",
            "axiz.metrics.percentile-histograms=false")
        .run(
            ctx -> {
              MetricsSettings settings = ctx.getBean(MetricsSettings.class);
              assertThat(settings).isNotNull();
              EventMetrics metrics = ctx.getBean(EventMetrics.class);
              MeterRegistry registry = ctx.getBean(MeterRegistry.class);
              registry.config().meterFilter(ctx.getBean(MeterFilter.class));
              metrics.value("transfer", 3, e -> e.tag("segment", "retail"));
              assertEquals(
                  1.0,
                  registry
                      .get(MetricNames.COUNT)
                      .tag("event", "transfer")
                      .tag("segment", "retail")
                      .counter()
                      .count());
            });
  }

  @Test
  void commonTagsNameTheServiceTheEnvironmentAndTheVersion() {
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("spring.application.name", "payments")
            .withProperty("axiz.config.environment", "qa");
    AxizMetricsProperties defaults =
        new AxizMetricsProperties(
            200,
            50,
            java.util.Set.of("web"),
            java.util.Map.of(),
            java.util.Map.of(),
            java.util.Map.of(),
            true,
            null,
            null);
    Properties build = new Properties();
    build.setProperty("version", "1.4.2");
    Tags fromBuild =
        AxizMetricsAutoConfiguration.commonTags(env, defaults, new BuildProperties(build));
    assertThat(fromBuild.stream().map(t -> t.getKey() + "=" + t.getValue()))
        .containsExactlyInAnyOrder("service=payments", "env=qa", "version=1.4.2", "domain=none");

    Tags unknown = AxizMetricsAutoConfiguration.commonTags(new MockEnvironment(), defaults, null);
    assertThat(unknown.stream().map(t -> t.getKey() + "=" + t.getValue()))
        .contains("service=application", "env=default", "version=unknown");

    AxizMetricsProperties declared =
        new AxizMetricsProperties(
            200,
            50,
            java.util.Set.of("web"),
            java.util.Map.of(),
            java.util.Map.of(),
            java.util.Map.of(),
            true,
            "payments",
            "2.0.0");
    assertThat(
            AxizMetricsAutoConfiguration.commonTags(env, declared, new BuildProperties(build))
                .stream()
                .map(t -> t.getKey() + "=" + t.getValue()))
        .contains("version=2.0.0", "domain=payments");
  }

  @Test
  @SuppressWarnings("unchecked")
  void theRegistryCustomizerAddsTheCommonTagsAndMetricsCanBeSwitchedOff() {
    runner
        .withPropertyValues("spring.application.name=payments")
        .run(
            ctx -> {
              SimpleMeterRegistry registry = new SimpleMeterRegistry();
              ctx.getBean(MeterRegistryCustomizer.class).customize(registry);
              registry.counter("probe").increment();
              assertEquals("payments", registry.get("probe").counter().getId().getTag("service"));
            });
    runner
        .withPropertyValues("axiz.metrics.enabled=false")
        .run(ctx -> assertThat(ctx).doesNotHaveBean(EventMetrics.class));
  }
}
