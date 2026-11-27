package pe.axiz.spring.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SchedulingProviderContractTest {
  @Test
  void missingProviderFailsClosedAndExplicitDisableOrCustomProviderAreSupported() {
    var runner =
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AxizSchedulingAutoConfiguration.class));
    runner.run(context -> assertThat(context).hasFailed());
    runner
        .withPropertyValues("axiz.scheduling.enabled=false")
        .run(context -> assertThat(context).hasNotFailed());
    var custom = mock(LockProvider.class);
    runner
        .withBean(LockProvider.class, () -> custom)
        .run(
            context -> {
              assertThat(context).hasNotFailed().hasSingleBean(LockProvider.class);
              assertThat(context.getBean(LockProvider.class)).isSameAs(custom);
            });
  }
}
