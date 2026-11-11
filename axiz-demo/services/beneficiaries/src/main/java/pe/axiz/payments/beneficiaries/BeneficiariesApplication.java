package pe.axiz.payments.beneficiaries;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Beneficiaries: Frequent beneficiaries of each customer. */
@SpringBootApplication
@SuppressWarnings("checkstyle:hideutilityclassconstructor")
public class BeneficiariesApplication {

  public static void main(String[] args) {
    SpringApplication.run(BeneficiariesApplication.class, args);
  }
}
