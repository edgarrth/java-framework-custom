package pe.axiz.spring.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import io.r2dbc.spi.ConnectionFactory;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.core.outbox.OutboxStore;
import pe.axiz.kit.r2dbc.ReactiveSql;

class OutboxLeaseValidationTest {
  private ApplicationContextRunner runner(boolean reactive) {
    var runner =
        new ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    reactive
                        ? AxizOutboxR2dbcAutoConfiguration.class
                        : AxizOutboxAutoConfiguration.class))
            .withBean(OutboxStore.class, () -> mock(OutboxStore.class))
            .withPropertyValues(
                "axiz.outbox.initialize-schema=never",
                "spring.kafka.bootstrap-servers=localhost:9092");
    return reactive
        ? runner
            .withBean(ConnectionFactory.class, () -> mock(ConnectionFactory.class))
            .withBean(ReactiveSql.class, () -> mock(ReactiveSql.class))
        : runner.withBean(DataSource.class, () -> mock(DataSource.class));
  }

  @Test
  void writersWithoutRelayDoNotRequireBrokerBudgetsAndStillVerifyTheirSchema() {
    for (boolean reactive : new boolean[] {false, true}) {
      runner(reactive)
          .withPropertyValues(
              "axiz.outbox.relay.lease-duration=2m", "axiz.outbox.relay.enabled=false")
          .withClassLoader(new FilteredClassLoader("io.micrometer.core.instrument"))
          .run(
              ctx -> {
                assertThat(ctx).hasNotFailed();
                org.mockito.Mockito.verify(ctx.getBean(OutboxStore.class)).verifySchema();
              });
      runner(reactive)
          .withPropertyValues("axiz.outbox.relay.lease-duration=120001ms")
          .run(ctx -> assertThat(ctx).hasNotFailed());
    }
  }

  @Test
  void exactUsableBudgetIsAcceptedAndOneNanosecondLessFails() {
    Duration lease = Duration.ofSeconds(10);
    Duration margin = pe.axiz.core.commons.LeaseTiming.margin(lease);
    Duration allowance = Duration.ofSeconds(2);
    Duration budget = lease.minus(margin).minus(allowance);
    assertDoesNotThrow(() -> OutboxLeaseValidation.validate(lease, budget, allowance));
    assertThrows(
        IllegalArgumentException.class,
        () -> OutboxLeaseValidation.validate(lease, budget.plusNanos(1), allowance));
    assertThrows(
        IllegalArgumentException.class,
        () -> OutboxLeaseValidation.validate(lease, Duration.ZERO, allowance));
    assertThrows(
        IllegalArgumentException.class,
        () -> OutboxLeaseValidation.validate(lease, budget, Duration.ZERO));
  }
}
