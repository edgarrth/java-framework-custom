package pe.axiz.payments.process;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Payment Process: saga de pagos. */
@SpringBootApplication
@SuppressWarnings("checkstyle:hideutilityclassconstructor")
public class PaymentProcessApplication {

  public static void main(String[] args) {
    SpringApplication.run(PaymentProcessApplication.class, args);
  }
}
