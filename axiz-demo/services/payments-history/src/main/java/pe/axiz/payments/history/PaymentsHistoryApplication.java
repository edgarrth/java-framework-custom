package pe.axiz.payments.history;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Payments History: historial de pagos de cada cliente: modelo de lectura proyectado a partir de
 * los eventos de órdenes de pago sobre R2DBC.
 */
@SpringBootApplication
@SuppressWarnings("checkstyle:hideutilityclassconstructor")
public class PaymentsHistoryApplication {

  public static void main(String[] args) {
    SpringApplication.run(PaymentsHistoryApplication.class, args);
  }
}
