package pe.axiz.payments.notifications.repository.local;

import java.net.URI;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import pe.axiz.core.crypto.FieldEncryptor;
import pe.axiz.payments.notifications.repository.ContactItem;
import pe.axiz.payments.notifications.repository.CustomerContact;
import pe.axiz.spring.dynamodb.DynamoRepositories;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * Fictitious contacts only for the explicitly enabled local demo: LocalStack on loopback (services
 * on the host) or the {@code localstack} container of the demo Compose network.
 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@ConditionalOnProperty(name = "demo.local.seed-contacts", havingValue = "true")
class LocalContactSeed {
  private static final List<String> LOCAL_STACK_HOSTS =
      List.of("localhost", "127.0.0.1", "[::1]", "localstack");

  @Bean
  ApplicationRunner seedLocalContacts(
      DynamoRepositories repositories,
      FieldEncryptor cipher,
      @Value("${axiz.aws.endpoint}") URI endpoint,
      @Value("${axiz.config.environment}") String environment,
      @Value("${axiz.dynamodb.table-prefix}") String tablePrefix) {
    if (!environment.equals("local")
        || !tablePrefix.equals("local-")
        || !LOCAL_STACK_HOSTS.contains(endpoint.getHost())) {
      throw new IllegalStateException(
          "Demo contact seed requires the local LocalStack and local tables");
    }
    return args -> {
      var table = repositories.create("notification-contacts", ContactItem.class);
      for (var contact :
          List.of(
              new CustomerContact("C-1001", "EMAIL", "ana.demo@example.com", "es-PE", true),
              new CustomerContact("C-1002", "SMS", "+51900000002", "en", true))) {
        try {
          table.insert(ContactItem.protect(contact, cipher), "customerId");
        } catch (ConditionalCheckFailedException existingContact) {
          // Preserve profile updates and existing ciphertext on every restart.
        }
      }
    };
  }
}
