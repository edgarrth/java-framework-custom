package pe.axiz.spring.sequence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;

class SequencePoolsTest {
  @Test
  void aSaturatedHikariBusinessPoolCannotDeadlockCounterReservations() throws Exception {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl("jdbc:h2:mem:sequence-pools;DB_CLOSE_DELAY=-1");
    config.setMaximumPoolSize(1);
    config.setConnectionTimeout(300);
    try (var main = new HikariDataSource(config)) {
      var transactions = new DataSourceTransactionManager(main);
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizSequenceAutoConfiguration.class))
          .withBean(javax.sql.DataSource.class, () -> main)
          .withBean(
              org.springframework.transaction.PlatformTransactionManager.class, () -> transactions)
          .withPropertyValues("axiz.sequence.sequences.operation.block-size=1")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(SequencePools.Jdbc.class).connections())
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
  void borrowedResourcesRemainOwnedByTheCallerAndPolicyIsValidated() throws Exception {
    var environment = new MockEnvironment();
    var source = new org.h2.jdbcx.JdbcDataSource();
    try (var resources = SequencePools.Jdbc.create(source, source, environment)) {
      assertThat(resources.connections()).isSameAs(source);
    }
    ConnectionFactory factory = mock(ConnectionFactory.class);
    try (var resources = SequencePools.Reactive.create(factory, factory, environment)) {
      assertThat(resources.connections()).isSameAs(factory);
    }
    try (var resources = SequencePools.Jdbc.create(source, null, environment)) {
      assertThat(resources.connections()).isSameAs(source);
    }
    var invalid = new MockEnvironment().withProperty("axiz.sequence.pool.max-size", "0");
    assertThrows(
        IllegalArgumentException.class,
        () -> SequencePools.Reactive.create(factory, null, invalid));
    var invalidTimeout =
        new MockEnvironment().withProperty("axiz.sequence.pool.acquire-timeout", "1ms");
    assertThrows(
        IllegalArgumentException.class,
        () -> SequencePools.Reactive.create(factory, null, invalidTimeout));
  }

  @Test
  void aMainPoolCredentialRotationAlsoReachesNewReservationConnections() throws Exception {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl("jdbc:h2:mem:sequence-rotation;DB_CLOSE_DELAY=-1");
    config.setUsername("sa");
    config.setPassword("");
    try (var main = new HikariDataSource(config);
        var resources = SequencePools.Jdbc.create(main, null, new MockEnvironment())) {
      var dedicated = (HikariDataSource) resources.connections();
      assertThat(dedicated.getCredentialsProvider().getCredentials())
          .isSameAs(main.getCredentials());
      main.setCredentials(
          com.zaxxer.hikari.util.Credentials.of("rotated-user", "rotated-password"));
      assertThat(dedicated.getCredentialsProvider().getCredentials())
          .isSameAs(main.getCredentials());
    }
  }

  @Test
  void reactiveReservationsUseAnIndependentBoundedPoolAndCloseOnlyTheirOwnPool() {
    ConnectionFactory driver = mock(ConnectionFactory.class);
    when(driver.create())
        .thenAnswer(ignored -> reactor.core.publisher.Mono.<io.r2dbc.spi.Connection>never());
    when(driver.getMetadata()).thenReturn(() -> "test");
    var main =
        new ConnectionPool(
            ConnectionPoolConfiguration.builder(driver).initialSize(0).maxSize(1).build());
    try (var resources = SequencePools.Reactive.create(main, null, new MockEnvironment())) {
      assertThat(resources.connections()).isInstanceOf(ConnectionPool.class).isNotSameAs(main);
      assertThat(((ConnectionPool) resources.connections()).unwrap()).isSameAs(driver);
    }
    assertThat(main.isDisposed()).isFalse();
    main.dispose();
  }
}
