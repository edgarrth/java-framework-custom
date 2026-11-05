package pe.axiz.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Runs the actual doctor command using a SELECT-only PostgreSQL account and legacy columns. */
@Timeout(60)
class DoctorOutboxPreflightIT {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17.6-alpine");
  private static final String PASSWORD = "local-doctor-read-only";

  @BeforeAll
  static void start() throws Exception {
    boolean docker = DockerClientFactory.instance().isDockerAvailable();
    if (Boolean.getBoolean("axiz.it.requireDocker")) assertTrue(docker, "Docker required");
    org.junit.jupiter.api.Assumptions.assumeTrue(docker);
    POSTGRES.start();
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var sql = connection.createStatement()) {
      sql.execute(
          "CREATE TABLE axiz_outbox(id TEXT PRIMARY KEY,status TEXT,attempts INT,"
              + "last_error TEXT,destination TEXT,created_at TIMESTAMPTZ,aggregate_type TEXT,aggregate_id TEXT)");
      sql.execute(
          "INSERT INTO axiz_outbox VALUES "
              + "('failed','FAILED',7,'preserved diagnostic','topic','2026-01-01','order','one'),"
              + "('published','PUBLISHED',0,NULL,'topic','2026-01-02','order','one'),"
              + "('pending','PENDING',0,NULL,'topic','2026-01-03','order','one'),"
              + "('isolated','FAILED',4,'isolated diagnostic','topic','2026-01-01',NULL,NULL)");
      sql.execute("CREATE USER doctor_reader WITH PASSWORD '" + PASSWORD + "'");
      sql.execute("GRANT SELECT ON axiz_outbox TO doctor_reader");
    }
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Test
  void reportPreservesFailuresAndIdentifiesPublishedAndPendingSuccessors(@TempDir Path directory)
      throws Exception {
    Path project = CliHarness.service(directory);
    var doctor = new CliHarness();
    doctor.env =
        Map.of("AXIZ_DOCTOR_DB_USER", "doctor_reader", "AXIZ_DOCTOR_DB_PASSWORD", PASSWORD);
    assertEquals(
        0,
        doctor.run(
            "doctor", "--dir", project.toString(), "--outbox-jdbc-url", POSTGRES.getJdbcUrl()),
        doctor.output());
    String report = doctor.output();
    assertTrue(report.contains("\"id\":\"failed\""));
    assertTrue(report.contains("\"publishedSuccessors\":true"));
    assertTrue(report.contains("\"pendingSuccessors\":true"));
    assertTrue(report.contains("preserved diagnostic"));
    assertTrue(report.contains("\"id\":\"isolated\""));
    assertTrue(report.contains("axiz.event"));
    assertFalse(report.contains(PASSWORD));
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var sql = connection.createStatement();
        var rows = sql.executeQuery("SELECT COUNT(*) FROM axiz_outbox WHERE status='FAILED'")) {
      assertTrue(rows.next());
      assertEquals(2, rows.getInt(1));
    }
  }
}
