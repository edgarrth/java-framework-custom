package pe.axiz.spring.lock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.core.lock.DistributedLock;
import pe.axiz.core.lock.LockTemplate;
import pe.axiz.spring.foundation.aws.AxizAwsIdentityAutoConfiguration;

class LockTest {

  @Test
  void memoryLockForTestsAndUnknownStoreFails() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
        .withConfiguration(AutoConfigurations.of(AxizLockAutoConfiguration.class))
        .withPropertyValues("axiz.config.environment=test")
        .run(
            ctx -> {
              assertEquals(
                  "ok",
                  ctx.getBean(LockTemplate.class)
                      .withLock(
                          "account:1", Duration.ofSeconds(5), Duration.ofSeconds(1), () -> "ok"));
              assertTrue(
                  ctx.getBean(DistributedLock.class)
                      .tryAcquire("account:2", Duration.ofSeconds(5))
                      .isPresent());
            });
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
        .withConfiguration(AutoConfigurations.of(AxizLockAutoConfiguration.class))
        .withPropertyValues("axiz.lock.store=redis")
        .run(ctx -> assertTrue(ctx.getStartupFailure() != null));
  }

  @Test
  void memoryLockIsRefusedWhenTheEnvironmentIsNotSet() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
        .withConfiguration(AutoConfigurations.of(AxizLockAutoConfiguration.class))
        .run(
            ctx -> {
              Throwable failure = ctx.getStartupFailure();
              assertTrue(failure != null);
              Throwable root = failure;
              while (root.getCause() != null) {
                root = root.getCause();
              }
              assertTrue(
                  String.valueOf(root.getMessage()).contains("axiz.config.environment is not set"));
            });
  }
}
