package pe.axiz.payments.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.springframework.batch.core.job.Job;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import pe.axiz.core.storage.ObjectMetadata;
import pe.axiz.core.storage.ObjectStorage;
import pe.axiz.payments.settlement.job.SettlementJobConfiguration;
import pe.axiz.payments.settlement.job.SettlementRunner;
import pe.axiz.spring.test.LocalStackTestConfiguration;
import pe.axiz.spring.test.PostgresTestConfiguration;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Arranca el servicio completo contra dependencias reales en contenedores (Testcontainers) y carga
 * un archivo de liquidación de extremo a extremo: entrada en S3, totales de control, filas en
 * Aurora, resumen en S3, una nueva ejecución idempotente y otra que vuelve a escribir un resumen
 * perdido. Requiere Docker.
 */
@SpringBootTest(
    properties = {
      "axiz.config.environment=test",
      "axiz.transport.allow-plain-http=true",
      "axiz.transport.allow-insecure-data-stores=true",
      "settlement.run-on-startup=false"
    })
@Import({PostgresTestConfiguration.class, LocalStackTestConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SettlementBatchApplicationTest {

  private static final String KEY = "in/settlement/NET-SETTLE-APPTEST.txt";
  private static final String SUMMARY = "out/settlement/NET-SETTLE-APPTEST.txt.summary.json";

  @Autowired SettlementRunner runner;
  @Autowired ObjectStorage storage;
  @Autowired S3Client s3;
  @Autowired JdbcTemplate jdbc;

  @Value("${axiz.storage.bucket}")
  String bucket;

  @Test
  void definesTheJob(@Autowired Job settlementJob) {
    assertThat(settlementJob.getName()).isEqualTo(SettlementJobConfiguration.NAME);
  }

  @Test
  void loadsAFileOnceAndWritesItsSummary(TestReporter evidence) throws Exception {
    s3.createBucket(b -> b.bucket(bucket));
    byte[] file = new ClassPathResource("settlement/NET-SETTLE-SAMPLE.txt").getContentAsByteArray();
    storage.put(KEY, new ByteArrayInputStream(file), ObjectMetadata.of("text/plain", file.length));

    assertThat(runner.load(KEY)).isZero();
    assertThat(lines()).isEqualTo(3);
    assertThat(storage.head(SUMMARY)).isPresent();

    // una nueva ejecución del mismo archivo no carga nada dos veces y termina igualmente con código
    // de salida 0
    assertThat(runner.load(KEY)).isZero();
    assertThat(lines()).isEqualTo(3);

    // el bucket perdió el resumen (p. ej., LocalStack se reinició): la nueva ejecución lo vuelve a
    // escribir
    storage.delete(SUMMARY);
    assertThat(runner.load(KEY)).isZero();
    assertThat(storage.head(SUMMARY)).isPresent();
    assertThat(lines()).isEqualTo(3);

    evidence.publishEntry("axiz.capability.metrics", "default");
  }

  private Integer lines() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM settlement_line WHERE file_key = ?", Integer.class, KEY);
  }
}
