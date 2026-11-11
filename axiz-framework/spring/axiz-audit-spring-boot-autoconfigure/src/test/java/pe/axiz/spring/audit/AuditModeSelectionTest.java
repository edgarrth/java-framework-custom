package pe.axiz.spring.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import pe.axiz.core.audit.AsyncAuditEventPublisher;
import pe.axiz.core.audit.AuditPublisher;
import pe.axiz.core.audit.LoggingAuditPublisher;
import reactor.core.publisher.Mono;

class AuditModeSelectionTest {
  private ApplicationContextRunner imperative() {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizAuditAutoConfiguration.class));
  }

  private ReactiveWebApplicationContextRunner reactive() {
    return new ReactiveWebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizAuditAutoConfiguration.class, AxizNativeAuditAutoConfiguration.class));
  }

  @Test
  void productionRequiresAnExplicitChoiceForBothFrameworkPublishers() {
    imperative()
        .run(
            ctx ->
                assertThat(ctx.getStartupFailure())
                    .hasStackTraceContaining("Declare axiz.audit.mode"));
    reactive()
        .run(
            ctx ->
                assertThat(ctx.getStartupFailure())
                    .hasStackTraceContaining("Declare axiz.audit.mode"));
    imperative()
        .withPropertyValues("axiz.audit.mode=")
        .run(
            ctx ->
                assertThat(ctx.getStartupFailure())
                    .hasStackTraceContaining("Declare axiz.audit.mode"));
  }

  @Test
  void developmentKeepsTheDefaultAndProductionAcceptsOnlyCanonicalChoices() {
    for (String environment : new String[] {"local", "test"}) {
      imperative()
          .withPropertyValues("axiz.config.environment=" + environment)
          .run(ctx -> assertThat(ctx).hasNotFailed());
      reactive()
          .withPropertyValues("axiz.config.environment=" + environment)
          .run(ctx -> assertThat(ctx).hasNotFailed());
    }
    imperative()
        .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("test"))
        .run(ctx -> assertThat(ctx).hasNotFailed());
    for (String selection : new String[] {"axiz.audit.mode=ASYNC", "axiz.audit.mode=SYNC"}) {
      imperative().withPropertyValues(selection).run(ctx -> assertThat(ctx).hasNotFailed());
      reactive().withPropertyValues(selection).run(ctx -> assertThat(ctx).hasNotFailed());
    }
  }

  @Test
  void removedSelectorsAreRejectedEvenWithACanonicalModeOrDevelopmentProfile() {
    for (String key : new String[] {"axiz.audit.async.enabled", "axiz.audit.durable.enabled"}) {
      for (String value : new String[] {"", "true", "false"}) {
        for (String environment : new String[] {"prod", "test", "local"}) {
          imperative()
              .withPropertyValues(
                  key + "=" + value,
                  "axiz.audit.mode=ASYNC",
                  "axiz.config.environment=" + environment)
              .run(
                  ctx ->
                      assertThat(ctx.getStartupFailure())
                          .hasStackTraceContaining(key + " was removed"));
          reactive()
              .withPropertyValues(
                  key + "=" + value,
                  "axiz.audit.mode=ASYNC",
                  "axiz.config.environment=" + environment)
              .run(
                  ctx ->
                      assertThat(ctx.getStartupFailure())
                          .hasStackTraceContaining(key + " was removed"));
        }
      }
    }
  }

  @Test
  void disabledAuditAndApplicationOwnedPublishersDoNotNeedFrameworkModeSettings() {
    imperative()
        .withPropertyValues("axiz.audit.enabled=false")
        .run(ctx -> assertThat(ctx).hasNotFailed());
    reactive()
        .withPropertyValues("axiz.audit.enabled=false")
        .run(ctx -> assertThat(ctx).hasNotFailed());
    imperative()
        .withBean(AuditPublisher.class, LoggingAuditPublisher::new)
        .run(ctx -> assertThat(ctx).hasNotFailed());
    reactive()
        .withBean(
            AsyncAuditEventPublisher.class, () -> event -> CompletableFuture.completedFuture(null))
        .run(ctx -> assertThat(ctx).hasNotFailed());
    reactive()
        .withBean(ReactiveAuditPublisher.class, () -> event -> Mono.empty())
        .run(ctx -> assertThat(ctx).hasNotFailed());
  }
}
