package pe.axiz.payments.orders;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Payments Business: Owner of payment orders. */
@SpringBootApplication
@SuppressWarnings("checkstyle:hideutilityclassconstructor")
public class PaymentOrdersApplication {

  public static void main(String[] args) {
    SpringApplication.run(PaymentOrdersApplication.class, args);
  }
}
