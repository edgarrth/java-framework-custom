package pe.axiz.payments.settlement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Settlement Batch: loads the daily settlement file of the payment network. */
@SpringBootApplication
@SuppressWarnings("checkstyle:hideutilityclassconstructor")
public class SettlementBatchApplication {

  public static void main(String[] args) {
    // a CronJob pod must end with the job's exit code (non-zero when the job failed)
    System.exit(
        SpringApplication.exit(SpringApplication.run(SettlementBatchApplication.class, args)));
  }
}
