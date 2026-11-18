package pe.axiz.spring.sequence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.util.Credentials;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import pe.axiz.spring.sequence.AxizSequenceAutoConfiguration;
import pe.axiz.spring.sequence.Sequences;

class JdbcSequencePoolTest {
  @Test
  void aSaturatedHikariBusinessPoolCannotDeadlockCounterReservations() throws Exception {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl("jdbc:h2:mem:sequence-pools;DB_CLOSE_DELAY=-1");
    config.setMaximumPoolSize(1);
    config.setConnectionTimeout(300);
    try (var main = new HikariDataSource(config)) {
      var transactions = new DataSourceTransactionManager(main);
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  AxizSequenceAutoConfiguration.class, AxizSequenceJdbcAutoConfiguration.class))
          .withBean(DataSource.class, () -> main)
          .withBean(PlatformTransactionManager.class, () -> transactions)
          .withPropertyValues("axiz.sequence.sequences.operation.block-size=1")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(JdbcSequenceConnections.class).connections())
                    .isNotSameAs(main);
                String value =
                    new TransactionTemplate(transactions)
                        .execute(
                            status -> {
                              assertThat(main.getHikariPoolMXBean().getActiveConnections())
                                  .isEqualTo(1);
                              return context.getBean(Sequences.class).next("operation");
                            });
                assertThat(value).isEqualTo("00000001");
              });
    }
  }

  @Test
  void aMainPoolCredentialRotationAlsoReachesNewReservationConnections() throws Exception {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl("jdbc:h2:mem:sequence-rotation;DB_CLOSE_DELAY=-1");
    config.setUsername("sa");
    config.setPassword("");
    try (var main = new HikariDataSource(config);
        var resources = JdbcSequenceConnections.create(main, null, new MockEnvironment())) {
      var dedicated = (HikariDataSource) resources.connections();
      assertThat(dedicated.getCredentialsProvider().getCredentials())
          .isSameAs(main.getCredentials());
      main.setCredentials(Credentials.of("rotated-user", "rotated-password"));
      assertThat(dedicated.getCredentialsProvider().getCredentials())
          .isSameAs(main.getCredentials());
    }
  }

  @Test
  void borrowedResourcesRemainOwnedByTheCaller() throws Exception {
    var environment = new MockEnvironment();
    var source = new JdbcDataSource();
    try (var resources = JdbcSequenceConnections.create(source, source, environment)) {
      assertThat(resources.connections()).isSameAs(source);
    }
    try (var resources = JdbcSequenceConnections.create(source, null, environment)) {
      assertThat(resources.connections()).isSameAs(source);
    }
  }
}
