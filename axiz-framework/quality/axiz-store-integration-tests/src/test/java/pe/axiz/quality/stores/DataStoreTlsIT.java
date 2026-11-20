package pe.axiz.quality.stores;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SslOptions;
import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.Option;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import javax.net.ssl.TrustManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.r2dbc.ConnectionFactoryBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import pe.axiz.core.commons.net.DataTransportSecurityPolicy;
import pe.axiz.quality.containers.TestContainerImages;
import pe.axiz.spring.aws.AxizAwsIdentityAutoConfiguration;
import pe.axiz.spring.jdbc.AxizJdbcTransportAutoConfiguration;
import pe.axiz.spring.r2dbc.AxizR2dbcAutoConfiguration;
import pe.axiz.spring.r2dbc.AxizR2dbcTransportAutoConfiguration;
import pe.axiz.spring.r2dbc.R2dbcTransportSecurity;
import pe.axiz.spring.r2dbc.ReaderDatabase;
import pe.axiz.spring.redis.AxizRedisTransportAutoConfiguration;
import pe.axiz.spring.test.BlockHoundExtension;

/** Real TLS handshakes, including certificate and hostname rejection, for managed data clients. */
@Timeout(180)
@ExtendWith(BlockHoundExtension.class)
class DataStoreTlsIT {
  private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(10);
  private static final DataTransportSecurityPolicy PRODUCTION =
      new DataTransportSecurityPolicy("prod", false);
  @TempDir Path temporary;
  private TlsMaterial material;

  @BeforeEach
  void certificates() throws Exception {
    Docker.require();
    material = TlsMaterial.create(temporary);
  }

  private PostgreSQLContainer postgres() throws Exception {
    var postgres = material.copyTo(TestContainerImages.postgres());
    postgres.withCommand(
        "sh",
        "-c",
        "umask 077 && cp /tmp/axiz-server.key /tmp/axiz-owned-server.key"
            + " && exec docker-entrypoint.sh postgres -c ssl=on"
            + " -c ssl_cert_file=/tmp/axiz-server.crt -c ssl_key_file=/tmp/axiz-owned-server.key");
    return postgres;
  }

  @Test
  void jdbcPoolAndIndependentFlywayValidateTheServerCertificateAndHostname() throws Exception {
    try (var postgres = postgres()) {
      postgres.start();
      for (var proof :
          List.of(
              new Peer("localhost", material.certificate(), true),
              new Peer("localhost", material.untrusted(), false),
              new Peer("127.0.0.1", material.certificate(), false))) {
        String url =
            "jdbc:postgresql://"
                + proof.host()
                + ":"
                + postgres.getMappedPort(5432)
                + "/"
                + postgres.getDatabaseName()
                + "?sslmode=verify-full&connectTimeout=5&sslrootcert="
                + proof.trust();
        try (var pool = new HikariDataSource()) {
          pool.setJdbcUrl(url);
          pool.setUsername(postgres.getUsername());
          pool.setPassword(postgres.getPassword());
          pool.setMaximumPoolSize(1);
          pool.setConnectionTimeout(5000);
          pool.setInitializationFailTimeout(-1);
          var flyway =
              Flyway.configure()
                  .dataSource(url, postgres.getUsername(), postgres.getPassword())
                  .load();
          new ApplicationContextRunner()
              .withConfiguration(AutoConfigurations.of(AxizJdbcTransportAutoConfiguration.class))
              .withPropertyValues("axiz.config.environment=prod")
              .withBean("pool", HikariDataSource.class, () -> pool)
              .withBean(Flyway.class, () -> flyway)
              .run(
                  context -> {
                    assertTrue(context.isRunning());
                    if (proof.valid()) {
                      try (var connection = pool.getConnection();
                          var statement = connection.createStatement();
                          var rows =
                              statement.executeQuery(
                                  "SELECT ssl FROM pg_stat_ssl WHERE pid=pg_backend_pid()")) {
                        assertTrue(rows.next());
                        assertTrue(rows.getBoolean(1), "actual connection must be encrypted");
                      }
                      assertEquals(0, flyway.info().all().length);
                    } else {
                      var failure = assertThrows(SQLException.class, pool::getConnection);
                      assertTlsFailure(failure);
                      assertTlsFailure(
                          assertThrows(RuntimeException.class, () -> flyway.info().all()));
                    }
                  });
        }
      }
    }
  }

  @Test
  void r2dbcWriterAndReaderRejectUntrustedCertificatesAndWrongHosts() throws Exception {
    try (var postgres = postgres()) {
      postgres.start();
      for (var proof :
          List.of(
              new Peer("localhost", material.certificate(), true),
              new Peer("localhost", material.untrusted(), false),
              new Peer("127.0.0.1", material.certificate(), false))) {
        var options =
            ConnectionFactoryOptions.builder()
                .option(ConnectionFactoryOptions.DRIVER, "postgresql")
                .option(ConnectionFactoryOptions.HOST, proof.host())
                .option(ConnectionFactoryOptions.PORT, postgres.getMappedPort(5432))
                .option(ConnectionFactoryOptions.DATABASE, postgres.getDatabaseName())
                .option(ConnectionFactoryOptions.USER, postgres.getUsername())
                .option(ConnectionFactoryOptions.PASSWORD, postgres.getPassword())
                .option(ConnectionFactoryOptions.CONNECT_TIMEOUT, Duration.ofSeconds(5))
                .option(Option.valueOf("sslMode"), "verify-full")
                .option(Option.valueOf("sslRootCert"), proof.trust().toString())
                .build();
        var writer = ConnectionFactoryBuilder.withOptions(options.mutate()).build();
        R2dbcTransportSecurity.validate(writer, PRODUCTION);
        assertQuery(DatabaseClient.create(writer), proof.valid());
        // The reader must go through the real Axiz builder, its pool and its final decorators.
        new ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    AxizAwsIdentityAutoConfiguration.class,
                    AxizR2dbcAutoConfiguration.class,
                    AxizR2dbcTransportAutoConfiguration.class))
            .withPropertyValues(
                "axiz.config.environment=prod",
                "axiz.r2dbc.reader.url=r2dbc:postgresql://"
                    + proof.host()
                    + ":"
                    + postgres.getMappedPort(5432)
                    + "/"
                    + postgres.getDatabaseName(),
                "spring.r2dbc.username=" + postgres.getUsername(),
                "spring.r2dbc.password=" + postgres.getPassword(),
                "spring.r2dbc.properties.sslMode=verify-full",
                "spring.r2dbc.properties.sslRootCert=" + proof.trust(),
                "axiz.r2dbc.reader.max-size=1")
            .run(
                context -> {
                  assertTrue(context.isRunning());
                  assertQuery(context.getBean(ReaderDatabase.class).client(), proof.valid());
                });
      }
    }
  }

  private static void assertQuery(DatabaseClient client, boolean valid) {
    var query =
        client
            .sql("SELECT ssl FROM pg_stat_ssl WHERE pid=pg_backend_pid()")
            .map(row -> row.get("ssl", Boolean.class))
            .one();
    if (valid) assertEquals(Boolean.TRUE, query.block(QUERY_TIMEOUT));
    else assertTlsFailure(assertThrows(RuntimeException.class, () -> query.block(QUERY_TIMEOUT)));
  }

  @Test
  void lettuceUsesTlsAndRejectsUntrustedCertificatesAndWrongHosts() throws Exception {
    try (var redis =
        material
            .copyTo(TestContainerImages.valkey())
            .withExposedPorts(6379)
            .withCommand(
                "valkey-server",
                "--port",
                "0",
                "--tls-port",
                "6379",
                "--tls-cert-file",
                "/tmp/axiz-server.crt",
                "--tls-key-file",
                "/tmp/axiz-server.key",
                "--tls-ca-cert-file",
                "/tmp/axiz-server.crt",
                "--tls-auth-clients",
                "no")
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1))) {
      redis.start();
      for (var proof :
          List.of(
              new Peer("localhost", material.certificate(), true),
              new Peer("localhost", material.untrusted(), false),
              new Peer("127.0.0.1", material.certificate(), false))) {
        var options =
            ClientOptions.builder()
                .sslOptions(SslOptions.builder().trustManager(trustManager(proof.trust())).build())
                .build();
        var client =
            LettuceClientConfiguration.builder()
                .clientOptions(options)
                .commandTimeout(Duration.ofSeconds(5))
                .useSsl()
                .and()
                .build();
        var factory =
            new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(proof.host(), redis.getMappedPort(6379)), client);
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AxizRedisTransportAutoConfiguration.class))
            .withPropertyValues("axiz.config.environment=prod")
            .withBean(LettuceConnectionFactory.class, () -> factory)
            .run(
                context -> {
                  assertTrue(context.isRunning());
                  if (proof.valid()) {
                    try (var connection = factory.getConnection()) {
                      assertEquals("PONG", connection.ping());
                    }
                  } else {
                    assertTlsFailure(assertThrows(RuntimeException.class, factory::getConnection));
                  }
                });
      }
    }
  }

  private static TrustManagerFactory trustManager(Path certificate) throws Exception {
    var trust = KeyStore.getInstance(KeyStore.getDefaultType());
    trust.load(null, null);
    try (var input = Files.newInputStream(certificate)) {
      trust.setCertificateEntry(
          "peer", CertificateFactory.getInstance("X.509").generateCertificate(input));
    }
    var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    factory.init(trust);
    return factory;
  }

  static void assertTlsFailure(Throwable failure) {
    StringBuilder causes = new StringBuilder();
    for (Throwable next = failure; next != null; next = next.getCause())
      causes.append(next.getClass().getName()).append(' ').append(next.getMessage()).append('\n');
    String detail = causes.toString().toLowerCase(java.util.Locale.ROOT);
    assertTrue(
        detail.contains("ssl")
            || detail.contains("certificate")
            || detail.contains("hostname")
            || detail.contains("subject alternative"),
        () -> "Expected TLS rejection, got " + causes);
  }

  private record Peer(String host, Path trust, boolean valid) {}
}
