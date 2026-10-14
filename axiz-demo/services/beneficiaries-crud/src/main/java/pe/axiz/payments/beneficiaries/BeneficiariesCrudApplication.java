package pe.axiz.payments.beneficiaries;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Beneficiaries Crud: Frequent beneficiaries of each customer. */
@SpringBootApplication
@SuppressWarnings("checkstyle:hideutilityclassconstructor")
public class BeneficiariesCrudApplication {

  public static void main(String[] args) {
    SpringApplication.run(BeneficiariesCrudApplication.class, args);
  }
}
