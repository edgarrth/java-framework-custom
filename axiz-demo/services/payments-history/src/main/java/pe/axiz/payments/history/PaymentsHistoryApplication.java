package pe.axiz.payments.history;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Payments History: Payment history of each customer: read model projected from the payment order
 * events over R2DBC.
 */
@SpringBootApplication
@SuppressWarnings("checkstyle:hideutilityclassconstructor")
public class PaymentsHistoryApplication {

  public static void main(String[] args) {
    SpringApplication.run(PaymentsHistoryApplication.class, args);
  }
}
