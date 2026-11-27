package pe.axiz.payments.settlement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Settlement Batch: carga el archivo diario de liquidación de la red de pagos. */
@SpringBootApplication
@SuppressWarnings("checkstyle:hideutilityclassconstructor")
public class SettlementBatchApplication {

  public static void main(String[] args) {
    // un pod de CronJob debe terminar con el código de salida del job (distinto de cero cuando el
    // job falló)
    System.exit(
        SpringApplication.exit(SpringApplication.run(SettlementBatchApplication.class, args)));
  }
}
