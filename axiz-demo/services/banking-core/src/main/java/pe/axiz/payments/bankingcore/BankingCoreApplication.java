package pe.axiz.payments.bankingcore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Banking Core: Anti-corruption layer of the internal core banking system. */
@SpringBootApplication
@SuppressWarnings("checkstyle:hideutilityclassconstructor")
public class BankingCoreApplication {

  public static void main(String[] args) {
    SpringApplication.run(BankingCoreApplication.class, args);
  }
}
