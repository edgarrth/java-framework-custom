package pe.axiz.payments.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.Job;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import pe.axiz.core.storage.ObjectMetadata;
import pe.axiz.core.storage.ObjectStorage;
import pe.axiz.payments.settlement.job.SettlementJobConfiguration;
import pe.axiz.payments.settlement.job.SettlementRunner;
import pe.axiz.spring.test.LocalStackTestConfiguration;
import pe.axiz.spring.test.PostgresTestConfiguration;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Starts the whole service against real dependencies in containers (Testcontainers) and loads a
 * settlement file end to end: S3 input, control totals, Aurora rows, summary on S3, an idempotent
 * rerun and a rerun that writes a lost summary again. Needs Docker.
 */
@SpringBootTest(properties = "settlement.run-on-startup=false")
@Import({PostgresTestConfiguration.class, LocalStackTestConfiguration.class})
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
  void loadsAFileOnceAndWritesItsSummary() throws Exception {
    s3.createBucket(b -> b.bucket(bucket));
    byte[] file = new ClassPathResource("settlement/NET-SETTLE-SAMPLE.txt").getContentAsByteArray();
    storage.put(KEY, new ByteArrayInputStream(file), ObjectMetadata.of("text/plain", file.length));

    assertThat(runner.load(KEY)).isZero();
    assertThat(lines()).isEqualTo(3);
    assertThat(storage.head(SUMMARY)).isPresent();

    // a rerun of the same file loads nothing twice and still ends with exit code 0
    assertThat(runner.load(KEY)).isZero();
    assertThat(lines()).isEqualTo(3);

    // the bucket lost the summary (e.g. LocalStack restarted): the rerun writes it again
    storage.delete(SUMMARY);
    assertThat(runner.load(KEY)).isZero();
    assertThat(storage.head(SUMMARY)).isPresent();
    assertThat(lines()).isEqualTo(3);
  }

  private Integer lines() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM settlement_line WHERE file_key = ?", Integer.class, KEY);
  }
}
