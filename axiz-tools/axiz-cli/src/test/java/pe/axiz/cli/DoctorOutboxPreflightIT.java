package pe.axiz.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import pe.axiz.adapter.outbox.jdbc.JdbcOutboxPreflight;
import pe.axiz.quality.containers.TestContainerImages;

/**
 * Ejecuta el comando doctor real con una cuenta de PostgreSQL de solo SELECT y el esquema v2
 * vigente.
 */
@Timeout(60)
class DoctorOutboxPreflightIT {
  private static final PostgreSQLContainer POSTGRES = TestContainerImages.postgres();
  private static final String PASSWORD = UUID.randomUUID().toString();

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
      try (var schema =
          Objects.requireNonNull(
              JdbcOutboxPreflight.class.getResourceAsStream("/axiz/sql/outbox-postgresql.sql"))) {
        for (String statement :
            new String(schema.readAllBytes(), StandardCharsets.UTF_8)
                .replaceAll("(?m)--.*", "")
                .split(";")) {
          if (!statement.isBlank()) sql.execute(statement);
        }
      }
      sql.execute(
          """
          INSERT INTO axiz_outbox (
            id,status,attempts,last_error,destination,created_at,aggregate_type,aggregate_id,
            event_type,envelope,next_attempt_at,aggregate_seq
          ) VALUES
          ('failed','FAILED',7,'preserved diagnostic','kafka:audit','2026-01-01',
            'order','one','changed','{}',NOW(),1),
          ('published','PUBLISHED',0,NULL,'kafka:audit','2026-01-02',
            'order','one','changed','{}',NOW(),2),
          ('pending','PENDING',0,NULL,'kafka:audit','2025-01-01',
            'order','one','changed','{}',NOW(),3),
          ('isolated','FAILED',4,'isolated diagnostic','kafka:audit','2026-01-01',
            'order','isolated','changed','{}',NOW(),1)
          """);
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
    assertTrue(report.contains("\"aggregateId\":\"isolated\""));
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
