package pe.axiz.payments.notifications;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Notifications Processor: Customer notifications and audit archive. */
@SpringBootApplication
@SuppressWarnings("checkstyle:hideutilityclassconstructor")
public class NotificationsProcessorApplication {

  public static void main(String[] args) {
    SpringApplication.run(NotificationsProcessorApplication.class, args);
  }
}
