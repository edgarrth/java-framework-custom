package pe.axiz.payments.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.Job;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import pe.axiz.spring.test.LocalStackTestConfiguration;
import pe.axiz.spring.test.PostgresTestConfiguration;

/**
 * Starts the whole service against real dependencies in containers (Testcontainers): wiring,
 * configuration, migrations and health. Needs Docker.
 */
@SpringBootTest(properties = "spring.batch.job.enabled=false")
@Import({PostgresTestConfiguration.class, LocalStackTestConfiguration.class})
class SettlementBatchApplicationTest {

  @Test
  void definesTheJob(@Autowired Job settlementBatchJob) {
    assertThat(settlementBatchJob.getName()).isEqualTo("settlement-batch");
  }
}
