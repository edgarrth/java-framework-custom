package pe.axiz.spring.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import net.ttddyy.observation.boot.SpringJdbcProxyFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.mock.env.MockEnvironment;
import pe.axiz.core.commons.net.DataTransportSecurityPolicy;

class JdbcTransportGuardTest {
  @Test
  void instrumentedPoolsAreRevalidatedThroughTheirActualTargetWithoutConnectionIo() {
    var env = new MockEnvironment().withProperty("axiz.config.environment", "prod");
    var sources =
        AxizJdbcTransportAutoConfiguration.axizJdbcTransportGuard(
            env,
            new DefaultListableBeanFactory().getBeanProvider(JdbcDataSourceTlsValidator.class));
    var guard =
        AxizJdbcTransportAutoConfiguration.Migrations.axizFlywayTransportGuard(env, sources);
    var opens = new AtomicInteger();
    try (var pool =
        new HikariDataSource() {
          @Override
          public Connection getConnection() throws SQLException {
            opens.incrementAndGet();
            return super.getConnection();
          }
        }) {
      pool.setJdbcUrl("jdbc:postgresql://never-connect.test/payments?sslmode=verify-full");
      sources.postProcessBeforeInitialization(pool, "pool");
      var springProxy =
          ProxyDataSourceBuilder.create(pool)
              .jdbcProxyFactory(new SpringJdbcProxyFactory())
              .buildProxy();
      for (DataSource proxy :
          java.util.List.of(
              springProxy,
              ProxyDataSourceBuilder.create(pool).buildProxy(),
              ProxyDataSourceBuilder.create(pool).build())) {
        var migration = Flyway.configure().dataSource(proxy).load();
        assertThat(guard.postProcessBeforeInitialization(migration, "observed"))
            .isSameAs(migration);
        pool.setJdbcUrl("jdbc:postgresql://never-connect.test/payments?sslmode=disable");
        assertThatThrownBy(() -> guard.postProcessBeforeInitialization(migration, "changed"))
            .isInstanceOf(IllegalStateException.class);
        pool.setJdbcUrl("jdbc:postgresql://never-connect.test/payments?sslmode=verify-full");
      }
      assertThat(opens).hasValue(0);
    }
    var empty = new ProxyDataSource();
    assertThatThrownBy(() -> sources.postProcessBeforeInitialization(empty, "empty"))
        .hasMessageContaining("DataSource target");
    empty.setDataSource(empty);
    assertThatThrownBy(() -> sources.postProcessBeforeInitialization(empty, "cycle"))
        .hasMessageContaining("Cyclic");
    empty.setDataSource(mock(DataSource.class));
    assertThatThrownBy(() -> sources.postProcessBeforeInitialization(empty, "opaque"))
        .hasMessageContaining("JdbcDataSourceTlsValidator");
  }

  @Test
  void bootMigrationSourcesAreCheckedWithoutOpeningConnections() {
    var env = new MockEnvironment().withProperty("axiz.config.environment", "prod");
    var sources =
        AxizJdbcTransportAutoConfiguration.axizJdbcTransportGuard(
            env,
            new DefaultListableBeanFactory().getBeanProvider(JdbcDataSourceTlsValidator.class));
    var guard =
        AxizJdbcTransportAutoConfiguration.Migrations.axizFlywayTransportGuard(env, sources);
    var source =
        new SimpleDriverDataSource(
            new org.postgresql.Driver(),
            "jdbc:postgresql://never-connect.test/payments?sslmode=verify-full");
    var flyway = Flyway.configure().dataSource(source).load();
    assertThatCode(() -> guard.postProcessBeforeInitialization(flyway, "migration"))
        .doesNotThrowAnyException();
    var properties = new java.util.Properties();
    properties.setProperty("sslmode", "verify-full");
    source.setConnectionProperties(properties);
    source.setUrl("jdbc:postgresql://never-connect.test/payments");
    assertThatCode(() -> guard.postProcessBeforeInitialization(flyway, "properties"))
        .doesNotThrowAnyException();
    source.setUrl("jdbc:postgresql://never-connect.test/payments?sslmode=disable");
    assertThatThrownBy(() -> guard.postProcessBeforeInitialization(flyway, "migration"))
        .isInstanceOf(IllegalStateException.class);
    source.setUrl(null);
    assertThatThrownBy(() -> sources.postProcessBeforeInitialization(source, "missing"))
        .hasMessageContaining("effective connection settings");
    source.setDriver(mock(java.sql.Driver.class));
    assertThatThrownBy(() -> sources.postProcessBeforeInitialization(source, "customDriver"))
        .hasMessageContaining("JdbcDataSourceTlsValidator");
    source.setDriver(null);
    assertThatThrownBy(() -> sources.postProcessBeforeInitialization(source, "noDriver"))
        .hasMessageContaining("JdbcDataSourceTlsValidator");
  }

  @Test
  void customSourcesNeedAnExplicitValidatorAndNeitherPathOpensAConnection() throws Exception {
    var env = new MockEnvironment().withProperty("axiz.config.environment", "prod");
    var beans = new DefaultListableBeanFactory();
    var source = mock(DataSource.class);
    var opens = new AtomicInteger();
    when(source.getConnection())
        .thenAnswer(
            invocation -> {
              opens.incrementAndGet();
              throw new AssertionError("Transport validation must precede connection I/O");
            });
    var guard =
        AxizJdbcTransportAutoConfiguration.axizJdbcTransportGuard(
            env, beans.getBeanProvider(JdbcDataSourceTlsValidator.class));
    assertThatThrownBy(() -> guard.postProcessBeforeInitialization(source, "custom"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("JdbcDataSourceTlsValidator");
    var validations = new AtomicInteger();
    beans.registerSingleton(
        "tls",
        new JdbcDataSourceTlsValidator() {
          @Override
          public boolean supports(DataSource candidate) {
            return candidate == source;
          }

          @Override
          public void validate(DataSource candidate, DataTransportSecurityPolicy policy) {
            validations.incrementAndGet();
            policy.requireVerified(true, true, "verified custom fixture");
          }
        });
    try (var wrapper = new HikariDataSource()) {
      wrapper.setDataSource(source);
      assertThat(guard.postProcessBeforeInitialization(wrapper, "wrapped")).isSameAs(wrapper);
      assertThat(validations).hasValue(1);
      assertThat(opens).hasValue(0);
    }
    var unrelated = new Object();
    assertThat(guard.postProcessBeforeInitialization(unrelated, "other")).isSameAs(unrelated);
  }

  @Test
  void opaqueOrCyclicPoolsAreRejectedBeforeInitialization() {
    var guard =
        AxizJdbcTransportAutoConfiguration.axizJdbcTransportGuard(
            new MockEnvironment().withProperty("axiz.config.environment", "prod"),
            new DefaultListableBeanFactory().getBeanProvider(JdbcDataSourceTlsValidator.class));
    try (var source = new HikariDataSource()) {
      assertThatThrownBy(() -> guard.postProcessBeforeInitialization(source, "unconfigured"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("effective connection settings");
      source.setDataSource(source);
      assertThatThrownBy(() -> guard.postProcessBeforeInitialization(source, "cycle"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Cyclic");
    }
    var running = mock(HikariDataSource.class);
    when(running.isRunning()).thenReturn(true);
    assertThatThrownBy(() -> guard.postProcessBeforeInitialization(running, "started"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("before starting");
  }

  @Test
  void flywayWithoutItsOwnUrlUsesTheVerifiedApplicationSource() {
    var env = new MockEnvironment().withProperty("axiz.config.environment", "prod");
    var sources =
        AxizJdbcTransportAutoConfiguration.axizJdbcTransportGuard(
            env,
            new DefaultListableBeanFactory().getBeanProvider(JdbcDataSourceTlsValidator.class));
    var guard =
        AxizJdbcTransportAutoConfiguration.Migrations.axizFlywayTransportGuard(env, sources);
    try (var source = new HikariDataSource()) {
      source.setJdbcUrl("jdbc:postgresql://never-connect.test/payments?sslmode=verify-full");
      var flyway = Flyway.configure().dataSource(source).load();
      assertThat(guard.postProcessBeforeInitialization(flyway, "migration")).isSameAs(flyway);
    }
    assertThatThrownBy(
            () -> guard.postProcessBeforeInitialization(Flyway.configure().load(), "missing"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("effective connection settings");
    var unrelated = new Object();
    assertThat(guard.postProcessBeforeInitialization(unrelated, "other")).isSameAs(unrelated);
  }

  @Test
  void guardsTheFinalPoolConfigurationBeforeItCanOpenAConnection() {
    var environment = new MockEnvironment().withProperty("axiz.config.environment", "prod");
    var validators =
        new DefaultListableBeanFactory().getBeanProvider(JdbcDataSourceTlsValidator.class);
    var guard = AxizJdbcTransportAutoConfiguration.axizJdbcTransportGuard(environment, validators);
    var opens = new AtomicInteger();
    try (var source =
        new HikariDataSource() {
          @Override
          public Connection getConnection() throws SQLException {
            opens.incrementAndGet();
            return super.getConnection();
          }
        }) {
      source.setJdbcUrl("jdbc:postgresql://never-connect.test/payments?sslmode=disable");
      source.addDataSourceProperty("sslmode", "verify-full");
      assertThatThrownBy(() -> guard.postProcessBeforeInitialization(source, "pool"))
          .isInstanceOf(IllegalStateException.class);
      assertThat(opens).hasValue(0);
      source.setJdbcUrl("jdbc:postgresql://never-connect.test/payments?sslmode=verify-full");
      assertThatCode(() -> guard.postProcessBeforeInitialization(source, "pool"))
          .doesNotThrowAnyException();
      assertThat(opens).hasValue(0);
    }
  }

  @Test
  void flywayOwnConnectionIsValidatedIndependentlyOfTheApplicationPool() {
    var environment = new MockEnvironment().withProperty("axiz.config.environment", "prod");
    var sources =
        AxizJdbcTransportAutoConfiguration.axizJdbcTransportGuard(
            environment,
            new DefaultListableBeanFactory().getBeanProvider(JdbcDataSourceTlsValidator.class));
    var guard =
        AxizJdbcTransportAutoConfiguration.Migrations.axizFlywayTransportGuard(
            environment, sources);
    var insecure =
        Flyway.configure()
            .dataSource(
                "jdbc:postgresql://never-connect.test/payments?sslmode=disable", "app", "fixture")
            .jdbcProperties(Map.of("sslmode", "verify-full"))
            .load();
    assertThatThrownBy(() -> guard.postProcessBeforeInitialization(insecure, "migration"))
        .isInstanceOf(IllegalStateException.class);
    var secure =
        Flyway.configure()
            .dataSource(
                "jdbc:postgresql://never-connect.test/payments?sslmode=verify-full",
                "app",
                "fixture")
            .load();
    assertThatCode(() -> guard.postProcessBeforeInitialization(secure, "migration"))
        .doesNotThrowAnyException();
  }
}
