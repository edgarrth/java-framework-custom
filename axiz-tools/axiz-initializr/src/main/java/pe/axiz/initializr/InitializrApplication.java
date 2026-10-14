package pe.axiz.initializr;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** axiz initializr: generates services from an OpenAPI contract. */
@SpringBootApplication
@ConfigurationPropertiesScan
@SuppressWarnings("checkstyle:hideutilityclassconstructor")
public class InitializrApplication {

  public static void main(String[] args) {
    SpringApplication.run(InitializrApplication.class, args);
  }
}
