package pe.axiz.payments.networkgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Payment Network Gateway: Facade and security for external systems. */
@SpringBootApplication
@SuppressWarnings("checkstyle:hideutilityclassconstructor")
public class PaymentNetworkGatewayApplication {

  public static void main(String[] args) {
    SpringApplication.run(PaymentNetworkGatewayApplication.class, args);
  }
}
