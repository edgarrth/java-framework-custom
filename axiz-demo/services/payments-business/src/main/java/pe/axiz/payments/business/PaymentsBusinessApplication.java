package pe.axiz.payments.business;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Payments Business: Owner of payment orders. */
@SpringBootApplication
@SuppressWarnings("checkstyle:hideutilityclassconstructor")
public class PaymentsBusinessApplication {

  public static void main(String[] args) {
    SpringApplication.run(PaymentsBusinessApplication.class, args);
  }
}
