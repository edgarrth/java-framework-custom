package pe.axiz.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Driver-boundary guarantees; the PostgreSQL integration test checks actual SELECT-only access. */
class DoctorPreflightSafetyTest {
  private static final String URL = "jdbc:postgresql://fixture.invalid/axiz";

  @Test
  void embeddedCredentialsAndUnsupportedDriversAreRejectedBeforeConnecting(@TempDir Path root)
      throws Exception {
    Path project = CliHarness.service(root);
    try (var drivers = mockStatic(DriverManager.class)) {
      for (String url :
          List.of(
              "jdbc:h2:mem:fixture",
              "jdbc:postgresql://user@host/db",
              URL + "?user=canary",
              URL + "?password=canary",
              URL + "?sslpassword=canary",
              URL + "?ssl=true&PaSsWoRd=canary")) {
        var doctor = new CliHarness();
        assertEquals(
            ExitCodes.PROBLEMS,
            doctor.run("doctor", "--dir", project.toString(), "--outbox-jdbc-url", url),
            doctor.output());
        assertTrue(doctor.output().contains("URL without credentials"));
        assertTrue(doctor.output().contains("1 errors, 0 warnings"), doctor.output());
        assertFalse(doctor.output().contains("canary"));
      }
      drivers.verifyNoInteractions();
    }
  }

  @Test
  void reportsUseBoundedReadOnlySnapshotsAndAccountForEveryFailedRow(@TempDir Path root)
      throws Exception {
    Path project = CliHarness.service(root);
    for (int count : new int[] {0, 2}) {
      var connection = mock(Connection.class);
      var statement = mock(PreparedStatement.class);
      var rows = mock(ResultSet.class);
      when(connection.prepareStatement(anyString())).thenReturn(statement);
      when(statement.executeQuery()).thenReturn(rows);
      if (count == 0) when(rows.next()).thenReturn(false);
      else when(rows.next()).thenReturn(true, true, false);
      when(rows.getString(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
      when(rows.getInt("attempts")).thenReturn(7);
      when(rows.getBoolean("published_successors")).thenReturn(true);
      var properties = new Properties();
      properties.setProperty("connectTimeout", "5");
      properties.setProperty("socketTimeout", "15");
      var doctor = new CliHarness();
      if (count > 0) {
        doctor.env =
            Map.of("AXIZ_DOCTOR_DB_USER", "reader", "AXIZ_DOCTOR_DB_PASSWORD", "fixture-canary");
        properties.setProperty("user", "reader");
        properties.setProperty("password", "fixture-canary");
      }
      try (var drivers = mockStatic(DriverManager.class)) {
        drivers
            .when(() -> DriverManager.getConnection(eq(URL), any(Properties.class)))
            .thenReturn(connection);
        assertEquals(
            0,
            doctor.run("doctor", "--dir", project.toString(), "--outbox-jdbc-url", URL),
            doctor.output());
        drivers.verify(() -> DriverManager.getConnection(URL, properties));
      }
      var calls = inOrder(connection, statement);
      calls.verify(connection).setReadOnly(true);
      calls.verify(connection).setAutoCommit(false);
      calls.verify(connection).setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      calls.verify(connection).prepareStatement(anyString());
      calls.verify(statement).executeQuery();
      calls.verify(connection).commit();
      calls.verify(connection).close();
      verify(rows).close();
      assertTrue(doctor.output().contains("0 errors, " + count + " warnings"), doctor.output());
      assertTrue(
          doctor.output().contains(count == 0 ? "no FAILED rows" : "2 FAILED rows retained"));
      if (count > 0) assertTrue(doctor.output().contains("\"publishedSuccessors\":true"));
      assertFalse(doctor.output().contains("fixture-canary"));
    }
  }

  @Test
  void providerFailuresAreReportedWithoutLeakingCredentials(@TempDir Path root) throws Exception {
    Path project = CliHarness.service(root);
    var doctor = new CliHarness();
    var unavailable = new SQLException("fixture-sensitive-provider-message");
    try (var drivers = mockStatic(DriverManager.class)) {
      drivers
          .when(() -> DriverManager.getConnection(eq(URL), any(Properties.class)))
          .thenThrow(unavailable);
      assertEquals(
          ExitCodes.PROBLEMS,
          doctor.run("doctor", "--dir", project.toString(), "--outbox-jdbc-url", URL),
          doctor.output());
    }
    assertTrue(doctor.output().contains("Outbox preflight failed (SQLException)"));
    assertTrue(doctor.output().contains("1 errors, 0 warnings"), doctor.output());
    assertFalse(doctor.output().contains("fixture-sensitive-provider-message"));
  }
}
