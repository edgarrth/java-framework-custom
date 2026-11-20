package pe.axiz.demo.providers;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.context.RequestMetadata;
import pe.axiz.core.featureflags.FeatureFlags;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.metrics.MetricNames;
import pe.axiz.core.sensitive.ObjectMasker;
import pe.axiz.core.sensitive.OutputChannel;
import pe.axiz.spring.featureflags.AxizFeatureFlagsAutoConfiguration;
import pe.axiz.spring.featureflags.FlagContexts;
import pe.axiz.spring.metrics.AxizMetricsAutoConfiguration;
import pe.axiz.spring.sensitive.AxizSensitiveAutoConfiguration;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** CPU-only policies apply to both web types and do not perform remote I/O. */
class PolicyRecipesTest {
  @Test
  void maskingMetricsAndFlagsExecuteInBothApplicationTypes(TestReporter evidence) {
    for (String stack : List.of("servlet", "reactive")) {
      RecipeStacks.runner(stack)
          .withConfiguration(
              AutoConfigurations.of(
                  AxizMetricsAutoConfiguration.class,
                  AxizSensitiveAutoConfiguration.class,
                  AxizFeatureFlagsAutoConfiguration.class))
          .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
          .withPropertyValues(
              "axiz.feature-flags.flags.statements.enabled=true",
              "axiz.feature-flags.flags.statements.channels=mobile",
              "axiz.sensitive.keys.account.level=HIGH",
              "axiz.sensitive.keys.account.mask=CARD")
          .run(
              context -> {
                Runnable operation =
                    () -> {
                      var masker = context.getBean(ObjectMasker.class);
                      assertThat(
                              masker
                                  .describe(
                                      Map.of("password", "synthetic-secret"), OutputChannel.LOG)
                                  .toString())
                          .doesNotContain("synthetic-secret");
                      assertThat(
                              masker
                                  .describe(
                                      Map.of("account", "4111111111111111"), OutputChannel.RESPONSE)
                                  .toString())
                          .doesNotContain("4111111111111111");
                      var flags = context.getBean(FeatureFlags.class);
                      try (var scope =
                          RequestContext.open(
                              RequestMetadata.builder("recipe-correlation", "recipe-request")
                                  .channel("mobile")
                                  .build())) {
                        assertThat(flags.isEnabled("statements", FlagContexts.current())).isTrue();
                      }
                      assertThat(flags.isEnabled("statements", FlagContexts.current())).isFalse();
                      context.getBean(EventMetrics.class).count("statement.read");
                      assertThat(
                              context
                                  .getBean(MeterRegistry.class)
                                  .get(MetricNames.COUNT)
                                  .tag("event", "statement.read")
                                  .counter()
                                  .count())
                          .isEqualTo(1);
                    };
                if (stack.equals("reactive"))
                  Mono.fromRunnable(operation)
                      .subscribeOn(Schedulers.parallel())
                      .block(Duration.ofSeconds(5));
                else operation.run();
                for (String capability : List.of("sensitive", "featureflags", "metrics"))
                  evidence.publishEntry("axiz.capability." + capability, stack);
              });
    }
  }
}
