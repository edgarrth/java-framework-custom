package pe.axiz.spring.foundation.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import pe.axiz.spring.foundation.AxizFoundationAutoConfiguration;
import pe.axiz.spring.foundation.WorkerHealthEnvironmentPostProcessor;

class WorkerHealthTest {
  private final ApplicationContextRunner context =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizFoundationAutoConfiguration.class));

  private static WorkerStatus status(WorkerLifecycle lifecycle) {
    return new WorkerStatus() {
      @Override
      public Snapshot workerSnapshot() {
        return lifecycle.workerSnapshot();
      }

      @Override
      public Duration shutdownGrace() {
        return lifecycle.shutdownGrace();
      }
    };
  }

  @Test
  void aWorkerErrorBecomesDownInsteadOfSilentlyLosingConsumption() {
    var entered = new CountDownLatch(1);
    var trigger = new CountDownLatch(1);
    var cleaned = new CountDownLatch(1);
    var lifecycle =
        new WorkerLifecycle(
            "failed-consumer",
            Duration.ZERO,
            () ->
                List.of(
                    () -> {
                      entered.countDown();
                      try {
                        trigger.await();
                      } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                      }
                      throw new AssertionError(
                          "synthetic sensitive diagnostic must never reach health");
                    }),
            cleaned::countDown);
    context
        .withBean(WorkerStatus.class, () -> status(lifecycle))
        .run(
            app -> {
              assertThat(app).hasNotFailed();
              lifecycle.start();
              try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var health = app.getBean("axizWorkersHealthIndicator", HealthIndicator.class);
                assertThat(health.health().getStatus()).isEqualTo(Status.UP);
                trigger.countDown();
                assertTrue(cleaned.await(5, TimeUnit.SECONDS));
                var stopped = new CountDownLatch(1);
                lifecycle.stop(stopped::countDown);
                assertTrue(stopped.await(5, TimeUnit.SECONDS));
                lifecycle.stop();
                assertThat(lifecycle.isRunning()).isFalse();
                assertThrows(IllegalStateException.class, lifecycle::start);
                assertThat(lifecycle.state()).isEqualTo(WorkerLifecycle.State.FAILED);
                assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
                assertThat(health.health().toString())
                    .doesNotContain("synthetic sensitive diagnostic");
                assertThat(lifecycle.workerSnapshot().alive()).isZero();
              } finally {
                trigger.countDown();
              }
            });
  }

  @Test
  void shutdownBudgetIsCheckedBeforeStartingWorkers() {
    var lifecycle = new WorkerLifecycle("budget", Duration.ofSeconds(30), List::of, () -> {});
    context
        .withPropertyValues("spring.lifecycle.timeout-per-shutdown-phase=20s")
        .withBean(WorkerStatus.class, () -> status(lifecycle))
        .run(
            app -> {
              assertThat(app).hasFailed();
              assertThat(app.getStartupFailure())
                  .hasMessageContaining("Worker shutdown grace plus 5s");
            });
    var valid = new WorkerLifecycle("valid", Duration.ofSeconds(15), List::of, () -> {});
    context
        .withPropertyValues("spring.lifecycle.timeout-per-shutdown-phase=20s")
        .withBean(WorkerStatus.class, () -> status(valid))
        .run(app -> assertThat(app).hasNotFailed());
  }

  @Test
  void healthIsOptionalAndReadinessDefaultsPreserveExplicitApplicationSettings() {
    context
        .withClassLoader(new FilteredClassLoader("org.springframework.boot.health"))
        .run(
            app -> {
              assertThat(app).hasNotFailed();
              assertThat(app).doesNotHaveBean("axizWorkersHealthIndicator");
            });
    var environment = new StandardEnvironment();
    var processor = new WorkerHealthEnvironmentPostProcessor();
    processor.postProcessEnvironment(environment, new SpringApplication());
    assertThat(environment.getProperty("management.endpoint.health.group.readiness.include"))
        .isEqualTo("readinessState,axizWorkers");
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "explicit",
                Map.of(
                    "management.endpoint.health.group.readiness.include",
                    "readinessState,axizWorkers,db")));
    assertThat(environment.getProperty("management.endpoint.health.group.readiness.include"))
        .isEqualTo("readinessState,axizWorkers,db");
    var disabled = new StandardEnvironment();
    disabled
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "disabled", Map.of("management.endpoint.health.probes.enabled", "false")));
    processor.postProcessEnvironment(disabled, new SpringApplication());
    assertThat(disabled.getProperty("management.endpoint.health.group.readiness.include")).isNull();
  }
}
