package pe.axiz.payments.orders;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Payment Orders: propietario de las órdenes de pago. */
@SpringBootApplication
@SuppressWarnings("checkstyle:hideutilityclassconstructor")
public class PaymentOrdersApplication {

  public static void main(String[] args) {
    SpringApplication.run(PaymentOrdersApplication.class, args);
  }
}
