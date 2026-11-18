package pe.axiz.spring.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.r2dbc.spi.ConnectionFactory;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.core.outbox.OutboxStore;
import pe.axiz.kit.r2dbc.ReactiveSql;
import pe.axiz.spring.outbox.jdbc.AxizOutboxJdbcAutoConfiguration;
import pe.axiz.spring.outbox.r2dbc.AxizOutboxR2dbcAutoConfiguration;

class OutboxProvidersTest {
  private ApplicationContextRunner runner(boolean reactive) {
    var runner =
        new ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    AxizOutboxAutoConfiguration.class,
                    reactive
                        ? AxizOutboxR2dbcAutoConfiguration.class
                        : AxizOutboxJdbcAutoConfiguration.class))
            .withBean(OutboxStore.class, () -> mock(OutboxStore.class))
            .withPropertyValues(
                "axiz.outbox.store=" + (reactive ? "r2dbc" : "jdbc"),
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
                Mockito.verify(ctx.getBean(OutboxStore.class)).verifySchema();
              });
      runner(reactive)
          .withPropertyValues("axiz.outbox.relay.lease-duration=120001ms")
          .run(ctx -> assertThat(ctx).hasNotFailed());
    }
  }
}
