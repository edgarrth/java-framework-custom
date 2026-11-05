package pe.axiz.spring.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.r2dbc.spi.ConnectionFactory;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
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
  void leaseMustExceedTheFrameworkKafkaDeliveryTimeoutInBothStacksWithoutMetricsOrRelay() {
    for (boolean reactive : new boolean[] {false, true}) {
      runner(reactive)
          .withPropertyValues("axiz.outbox.relay.lease-duration=2m")
          .run(ctx -> assertThat(ctx).hasFailed());
      runner(reactive)
          .withPropertyValues("axiz.outbox.relay.lease-duration=120001ms")
          .run(ctx -> assertThat(ctx).hasNotFailed());
    }
  }
}
