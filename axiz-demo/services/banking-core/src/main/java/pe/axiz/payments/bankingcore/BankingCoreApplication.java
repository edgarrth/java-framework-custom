package pe.axiz.payments.bankingcore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Core Integration: Anti-corruption layer of the core banking system and the payment network. */
@SpringBootApplication
@SuppressWarnings("checkstyle:hideutilityclassconstructor")
public class BankingCoreApplication {

  public static void main(String[] args) {
    SpringApplication.run(BankingCoreApplication.class, args);
  }
}
