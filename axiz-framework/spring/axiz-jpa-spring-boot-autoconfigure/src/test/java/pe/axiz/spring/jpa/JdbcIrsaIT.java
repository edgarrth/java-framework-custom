package pe.axiz.spring.jpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.postgresql.PostgreSQLContainer;
import pe.axiz.spring.foundation.aws.AxizAwsIdentityAutoConfiguration;
import pe.axiz.test.support.AwsIdentityFixture;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.utils.SdkAutoCloseable;
import software.amazon.jdbc.HostSpecBuilder;
import software.amazon.jdbc.hostavailability.SimpleHostAvailabilityStrategy;

/**
 * The wrapper's actual plugin chain connects/reconnects using temporary IAM tokens, never node
 * credentials.
 */
class JdbcIrsaIT {
  @Test
  void onePoolSessionServesConcurrentConnectionsReplacementAndFailedDatabaseRetry()
      throws Exception {
    try (var postgres =
        new PostgreSQLContainer("postgres:17.6-alpine")
            .withEnv("POSTGRES_HOST_AUTH_METHOD", "trust")) {
      postgres.start();
      for (int count : new int[] {1, 5}) {
        try (var fixture = new AwsIdentityFixture(true)) {
          Path log = temporary.resolve("pool-" + count + ".log");
          var builder =
              new ProcessBuilder(
                      Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                      "-cp",
                      System.getProperty(
                          "surefire.test.class.path", System.getProperty("java.class.path")),
                      PoolProbe.class.getName(),
                      postgres.getJdbcUrl(),
                      postgres.getUsername(),
                      Integer.toString(count))
                  .redirectErrorStream(true)
                  .redirectOutput(log.toFile());
          builder.environment().clear();
          builder.environment().putAll(fixture.environment());
          Process process = builder.start();
          try {
            assertTrue(process.waitFor(45, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), Files.readString(log));
          } finally {
            if (process.isAlive()) process.destroyForcibly();
          }
          assertEquals(
              1,
              fixture.stsRequests(),
              "One context session, including pool replacement and retry");
          assertEquals(0, fixture.imdsRequests());
          assertEquals(0, fixture.invalidRequests());
        }
      }
    }
  }

  @Test
  void hikariInjectsAndBorrowsApplicationCredentialsBeforeOpeningTheWrapperConnection() {
    var calls = new AtomicInteger();
    var closes = new AtomicInteger();
    class Source implements AwsCredentialsProvider, SdkAutoCloseable {
      @Override
      public AwsCredentials resolveCredentials() {
        calls.incrementAndGet();
        return AwsBasicCredentials.create("D3_JDBC", "fixture");
      }

      @Override
      public void close() {
        closes.incrementAndGet();
      }
    }
    var source = new Source();
    try (var postgres =
        new PostgreSQLContainer("postgres:17.6-alpine")
            .withEnv("POSTGRES_HOST_AUTH_METHOD", "trust")) {
      postgres.start();
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
          .withUserConfiguration(AxizJpaAutoConfiguration.IamConfiguration.class)
          .withPropertyValues("axiz.jpa.aws-wrapper.enabled=true", "axiz.jpa.aws-wrapper.iam=true")
          .withBean("identity", Source.class, () -> source)
          .withBean(
              "dataSource",
              HikariDataSource.class,
              () -> {
                var pool = new HikariDataSource();
                pool.setDriverClassName("software.amazon.jdbc.Driver");
                pool.setJdbcUrl(postgres.getJdbcUrl().replace("jdbc:", "jdbc:aws-wrapper:"));
                pool.setUsername(postgres.getUsername());
                pool.setMaximumPoolSize(1);
                pool.addDataSourceProperty("iamRegion", "us-east-1");
                pool.addDataSourceProperty(
                    "iamHost", "db.cluster-example.us-east-1.rds.amazonaws.com");
                pool.addDataSourceProperty("wrapperDialect", "pg");
                pool.addDataSourceProperty("wrapperProfileName", JdbcIamProfiles.register(false));
                return pool;
              })
          .run(
              ctx -> {
                org.assertj.core.api.Assertions.assertThat(ctx).hasNotFailed();
                var pool = ctx.getBean(HikariDataSource.class);
                try (var connection = pool.getConnection();
                    var statement = connection.createStatement();
                    var result = statement.executeQuery("select 1")) {
                  assertTrue(result.next());
                  assertEquals(1, result.getInt(1));
                }
                assertTrue(calls.get() > 0);
                pool.close();
                assertEquals(0, closes.get());
              });
      assertEquals(1, closes.get());
    }
  }

  @TempDir Path temporary;

  @Test
  void connectsAndReconnectsWithExclusiveWebIdentityCredentials() throws Exception {
    try (var postgres =
        new PostgreSQLContainer("postgres:17.6-alpine")
            .withEnv("POSTGRES_HOST_AUTH_METHOD", "trust")) {
      postgres.start();
      for (boolean accepted : new boolean[] {true, false}) {
        try (var fixture = new AwsIdentityFixture(accepted)) {
          Path log = temporary.resolve("jdbc-" + accepted + ".log");
          var builder =
              new ProcessBuilder(
                      Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                      "-cp",
                      System.getProperty(
                          "surefire.test.class.path", System.getProperty("java.class.path")),
                      Probe.class.getName(),
                      Boolean.toString(accepted),
                      postgres.getJdbcUrl(),
                      postgres.getUsername())
                  .redirectErrorStream(true)
                  .redirectOutput(log.toFile());
          builder.environment().clear();
          builder.environment().putAll(fixture.environment());
          Process process = builder.start();
          try {
            assertTrue(process.waitFor(45, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), Files.readString(log));
          } finally {
            if (process.isAlive()) process.destroyForcibly();
          }
          assertTrue(fixture.stsRequests() > 0);
          assertEquals(0, fixture.imdsRequests());
          assertEquals(0, fixture.invalidRequests());
        }
      }
    }
  }

  public static final class Probe {
    private Probe() {}

    public static void main(String[] args) throws Exception {
      boolean accepted = Boolean.parseBoolean(args[0]);
      try {
        PoolProbe.main(new String[] {args[1], args[2], "1"});
        if (!accepted) throw new AssertionError("Invalid IRSA accepted");
      } catch (Exception failure) {
        if (accepted) throw failure;
        Throwable cause = failure;
        while (cause != null
            && !(String.valueOf(cause.getMessage()).contains("InvalidIdentityToken")
                || String.valueOf(cause.getMessage()).contains("fixture denied")))
          cause = cause.getCause();
        if (cause == null) throw failure;
      }
    }
  }

  public static final class PoolProbe {
    private PoolProbe() {}

    public static void main(String[] args) {
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
          .withUserConfiguration(AxizJpaAutoConfiguration.IamConfiguration.class)
          .withPropertyValues("axiz.jpa.aws-wrapper.enabled=true", "axiz.jpa.aws-wrapper.iam=true")
          .withBean(
              "dataSource",
              HikariDataSource.class,
              () -> {
                var pool = new HikariDataSource();
                pool.setDriverClassName("software.amazon.jdbc.Driver");
                pool.setJdbcUrl(args[0].replace("jdbc:", "jdbc:aws-wrapper:"));
                pool.setUsername(args[1]);
                pool.setMinimumIdle(0);
                pool.setInitializationFailTimeout(-1);
                pool.setMaximumPoolSize(Integer.parseInt(args[2]));
                pool.addDataSourceProperty("iamRegion", "us-east-1");
                pool.addDataSourceProperty(
                    "iamHost", "db.cluster-example.us-east-1.rds.amazonaws.com");
                pool.addDataSourceProperty("wrapperDialect", "pg");
                pool.addDataSourceProperty("wrapperProfileName", JdbcIamProfiles.register(false));
                return pool;
              })
          .run(
              ctx -> {
                org.assertj.core.api.Assertions.assertThat(ctx).hasNotFailed();
                assertEquals(0, ctx.getBeanNamesForType(AwsCredentialsProvider.class).length);
                var pool = ctx.getBean(HikariDataSource.class);
                var connections = new ArrayList<Connection>();
                String id = pool.getDataSourceProperties().getProperty("wrapperProfileName");
                assertTrue(id.startsWith(JdbcIamCredentialScopes.PREFIX));
                try {
                  for (int i = 0; i < Integer.parseInt(args[2]); i++)
                    connections.add(pool.getConnection());
                  var evicted = connections.removeFirst();
                  pool.evictConnection(evicted);
                  evicted.close();
                  connections.add(pool.getConnection());
                  for (var connection : connections) {
                    try (var statement = connection.createStatement();
                        var rs = statement.executeQuery("select 1")) {
                      assertTrue(rs.next());
                      assertEquals(1, rs.getInt(1));
                    }
                  }
                  var properties = new Properties();
                  properties.putAll(pool.getDataSourceProperties());
                  properties.setProperty("user", args[1]);
                  var plugin =
                      (AxizJdbcIamPluginFactory.Plugin)
                          new AxizJdbcIamPluginFactory()
                              .getInstance(
                                  org.mockito.Mockito.mock(
                                      software.amazon.jdbc.util.FullServicesContainer.class),
                                  properties);
                  var host =
                      new HostSpecBuilder(new SimpleHostAvailabilityStrategy())
                          .host("localhost")
                          .port(5432)
                          .build();
                  {
                    var failed = new SQLException("fixture refused connection", "08001");
                    assertEquals(
                        failed,
                        org.junit.jupiter.api.Assertions.assertThrows(
                            SQLException.class,
                            () ->
                                plugin.connect(
                                    "jdbc:postgresql:",
                                    host,
                                    properties,
                                    false,
                                    () -> {
                                      throw failed;
                                    })));
                    try (var recovered =
                        plugin.forceConnect(
                            "jdbc:postgresql:",
                            host,
                            properties,
                            false,
                            () -> DriverManager.getConnection(args[0], properties))) {
                      assertTrue(!recovered.isClosed());
                    }
                  }
                } finally {
                  for (var connection : connections) connection.close();
                }
              });
    }
  }
}
