package pe.axiz.spring.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptStatementFailedException;

/**
 * Los adaptadores de Spring mantienen el código JDBC del framework dentro de transacciones
 * gestionadas por Spring.
 */
class SpringJdbcAdaptersTest {

  private final DataSource dataSource =
      new DriverManagerDataSource(
          "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
  private final JdbcTemplate jdbc = new JdbcTemplate(dataSource);
  private final SpringTransactionRunner tx =
      new SpringTransactionRunner(new DataSourceTransactionManager(dataSource));

  private int rows() {
    Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM movements", Integer.class);
    return n == null ? 0 : n;
  }

  private void createTable() {
    jdbc.execute("CREATE TABLE movements (id INT PRIMARY KEY)");
  }

  @Test
  void rewritingTouchesIdentifiersButNeverCommentsOrLiteralValues() {
    var initializer =
        new SchemaInitializer(
            dataSource,
            "probe",
            SchemaInitializer.Mode.ALWAYS,
            Map.of("axiz_probe", "customer_probe"));
    assertEquals(
        "CREATE TABLE customer_probe (value TEXT DEFAULT 'axiz_probe'); -- axiz_probe\n"
            + "/* axiz_probe */ CREATE INDEX customer_probe_idx ON customer_probe (value);",
        initializer.configure(
            "CREATE TABLE axiz_probe (value TEXT DEFAULT 'axiz_probe'); -- axiz_probe\n"
                + "/* axiz_probe */ CREATE INDEX axiz_probe_idx ON axiz_probe (value);"));
    var tooLong =
        new SchemaInitializer(
            dataSource,
            "probe",
            SchemaInitializer.Mode.ALWAYS,
            Map.of("axiz_probe", "x".repeat(63)));
    assertThrows(
        IllegalArgumentException.class, () -> tooLong.configure("CREATE INDEX axiz_probe_stale"));
  }

  @Test
  void workIsCommittedAndItsResultReturned() throws Exception {
    createTable();
    String result =
        tx.inTransaction(
            () -> {
              jdbc.update("INSERT INTO movements VALUES (1)");
              return "done";
            });
    assertEquals("done", result);
    assertEquals(1, rows());
  }

  @Test
  void aCheckedExceptionRollsBackAndReachesTheCallerUnwrapped() {
    createTable();
    IOException failure = new IOException("ledger unavailable");
    IOException thrown =
        assertThrows(
            IOException.class,
            () ->
                tx.inTransaction(
                    () -> {
                      jdbc.update("INSERT INTO movements VALUES (1)");
                      throw failure;
                    }));
    assertSame(failure, thrown);
    assertEquals(0, rows());
  }

  @Test
  void aRuntimeExceptionRollsBackToo() {
    createTable();
    IllegalStateException failure = new IllegalStateException("limit exceeded");
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                tx.inTransaction(
                    () -> {
                      jdbc.update("INSERT INTO movements VALUES (1)");
                      throw failure;
                    }));
    assertSame(failure, thrown);
    assertEquals(0, rows());
  }

  @Test
  void insideATransactionTheProviderHandsOutTheTransactionConnectionAndKeepsItOpen()
      throws Exception {
    SpringConnectionProvider connections = new SpringConnectionProvider(dataSource);
    tx.inTransaction(
        () -> {
          Connection first = connections.get();
          connections.release(first);
          assertFalse(first.isClosed());
          Connection second = connections.get();
          assertSame(first, second);
          connections.release(second);
          return null;
        });
    Connection outside = connections.get();
    connections.release(outside);
    assertTrue(outside.isClosed());
  }

  @Test
  void theSchemaIsCreatedOnH2InEmbeddedModeAndNeverWhenDisabled() {
    new SchemaInitializer(dataSource, "probe", SchemaInitializer.Mode.NEVER).afterPropertiesSet();
    assertThrows(
        Exception.class,
        () -> jdbc.queryForObject("SELECT COUNT(*) FROM axiz_probe", Integer.class));

    new SchemaInitializer(dataSource, "probe", SchemaInitializer.Mode.EMBEDDED)
        .afterPropertiesSet();
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM axiz_probe", Integer.class));
    // los scripts son idempotentes, por lo que una ejecución ALWAYS después de un arranque previo
    // es inofensiva
    new SchemaInitializer(dataSource, "probe", SchemaInitializer.Mode.ALWAYS).afterPropertiesSet();
  }

  @Test
  void configuredNamesAreUsedAndUnsafeIdentifiersFailBeforeAnyDdl() {
    new SchemaInitializer(
            dataSource,
            "probe",
            SchemaInitializer.Mode.ALWAYS,
            Map.of("axiz_probe", "business_probe"))
        .afterPropertiesSet();
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM business_probe", Integer.class));
    assertThrows(
        Exception.class,
        () -> jdbc.queryForObject("SELECT COUNT(*) FROM axiz_probe", Integer.class));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SchemaInitializer(
                dataSource,
                "probe",
                SchemaInitializer.Mode.ALWAYS,
                Map.of("axiz_probe", "x; DROP TABLE business_probe")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SchemaInitializer(
                dataSource,
                "probe",
                SchemaInitializer.Mode.ALWAYS,
                Map.of("axiz_probe", "x".repeat(64))));
  }

  @Test
  void aMissingScriptFailsTheStartup() {
    SchemaInitializer missing =
        new SchemaInitializer(dataSource, "missing", SchemaInitializer.Mode.ALWAYS);
    Exception e = assertThrows(Exception.class, missing::afterPropertiesSet);
    assertFalse(e instanceof ScriptStatementFailedException);
  }

  @Test
  void theDialectIsDetectedFromTheConnection() {
    assertTrue(SqlDialects.isH2(dataSource));
    DataSource broken =
        new DriverManagerDataSource("jdbc:h2:mem:x", "sa", "") {
          @Override
          public Connection getConnection() throws SQLException {
            throw new SQLException("down");
          }
        };
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> SqlDialects.product(broken));
    assertTrue(e.getMessage().contains("database product"));
  }
}
