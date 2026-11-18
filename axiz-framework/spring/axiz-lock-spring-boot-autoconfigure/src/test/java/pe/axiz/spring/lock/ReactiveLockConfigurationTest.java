package pe.axiz.spring.lock;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import pe.axiz.core.lock.AsyncDistributedLock;
import pe.axiz.core.lock.DistributedLock;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ReactiveLockConfigurationTest {
  private final ReactiveWebApplicationContextRunner runner =
      new ReactiveWebApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizLockAutoConfiguration.class))
          .withPropertyValues("axiz.config.environment=test", "axiz.lock.store=memory");

  @Test
  void nativeMemoryLockIsSelectedAndReleasedAfterBusinessFailure() {
    runner.run(
        context -> {
          assertThat(context)
              .hasNotFailed()
              .hasSingleBean(AsyncDistributedLock.class)
              .doesNotHaveBean(DistributedLock.class);
          var locks = context.getBean(ReactiveLocks.class);
          var failure = new IllegalStateException("business failure");
          StepVerifier.create(
                  locks.withLock(
                      "account", Duration.ofSeconds(2), Duration.ZERO, () -> Mono.error(failure)))
              .expectErrorMatches(error -> error == failure)
              .verify();
          StepVerifier.create(
                  locks.withLock(
                      "account", Duration.ofSeconds(2), Duration.ZERO, () -> Mono.just("released")))
              .expectNext("released")
              .verifyComplete();
        });
  }

  @Test
  void unsafeOrIncompleteStorageSelectionCannotStart() {
    for (String value :
        new String[] {
          "axiz.config.environment=prod",
          "axiz.lock.store=redis",
          "axiz.lock.store=",
          "axiz.lock.poll-interval=0ms"
        }) {
      runner.withPropertyValues(value).run(context -> assertThat(context).hasFailed());
    }
  }
}
