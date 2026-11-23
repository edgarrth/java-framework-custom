package pe.axiz.payments.bankingcore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Banking Core: capa anticorrupción del sistema core bancario interno. */
@SpringBootApplication
@SuppressWarnings("checkstyle:hideutilityclassconstructor")
public class BankingCoreApplication {

  public static void main(String[] args) {
    SpringApplication.run(BankingCoreApplication.class, args);
  }
}
