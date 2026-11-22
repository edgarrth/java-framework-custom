package pe.axiz.payments.notifications.repository.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.core.crypto.AesGcm;
import pe.axiz.core.crypto.FieldEncryptor;
import pe.axiz.core.crypto.StaticKeyProvider;
import pe.axiz.spring.dynamodb.DynamoRepositories;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;

class LocalContactSeedTest {
  @Test
  void createsOnlyEncryptedContactsAndPreservesExistingRowsOnRestart() throws Exception {
    var stored = new LinkedHashMap<String, Map<String, AttributeValue>>();
    var sdk = mock(DynamoDbClient.class);
    when(sdk.putItem(any(PutItemRequest.class)))
        .thenAnswer(
            call -> {
              PutItemRequest request = call.getArgument(0);
              assertThat(request.tableName()).isEqualTo("local-notification-contacts");
              assertThat(request.conditionExpression()).contains("attribute_not_exists");
              if (stored.putIfAbsent(request.item().get("customerId").s(), request.item()) != null)
                throw ConditionalCheckFailedException.builder().message("already exists").build();
              return PutItemResponse.builder().build();
            });
    var repositories =
        new DynamoRepositories(
            DynamoDbEnhancedClient.builder().dynamoDbClient(sdk).build(), "local-");
    try (var keys = new StaticKeyProvider("v1", Map.of("v1", AesGcm.newKey()))) {
      var cipher = new FieldEncryptor(keys);
      var seed = new LocalContactSeed();
      var runner =
          seed.seedLocalContacts(
              repositories, cipher, URI.create("http://localhost:4566"), "local", "local-");
      runner.run(new DefaultApplicationArguments());
      assertThat(stored).hasSize(2);
      var first = Map.copyOf(stored);
      runner.run(new DefaultApplicationArguments());
      assertThat(stored).isEqualTo(first);
      assertThat(stored.toString()).doesNotContain("ana.demo@example.com", "+51900000002");
      assertThat(
              cipher.decrypt(
                  stored.get("C-1001").get("address").s(), "notification.address", "6:C-1001EMAIL"))
          .isEqualTo("ana.demo@example.com");
      assertThrows(
          IllegalStateException.class,
          () ->
              seed.seedLocalContacts(
                  repositories,
                  cipher,
                  URI.create("https://dynamodb.us-east-1.amazonaws.com"),
                  "local",
                  "local-"));
      assertThat(
              seed.seedLocalContacts(
                  repositories, cipher, URI.create("http://localstack:4566"), "local", "local-"))
          .isNotNull();
      assertThrows(
          IllegalStateException.class,
          () ->
              seed.seedLocalContacts(
                  repositories,
                  cipher,
                  URI.create("http://localstack.example.com:4566"),
                  "local",
                  "local-"));
      assertThrows(
          IllegalStateException.class,
          () ->
              seed.seedLocalContacts(
                  repositories, cipher, URI.create("http://localhost:4566"), "prod", "local-"));
      assertThrows(
          IllegalStateException.class,
          () ->
              seed.seedLocalContacts(
                  repositories, cipher, URI.create("http://localhost:4566"), "local", "prod-"));
    }
  }

  @Test
  void seedIsAbsentWithoutBothLocalProfileAndExplicitOptIn() {
    new ApplicationContextRunner()
        .withUserConfiguration(LocalContactSeed.class)
        .withPropertyValues("demo.local.seed-contacts=true")
        .run(context -> assertThat(context).doesNotHaveBean("seedLocalContacts"));
    new ApplicationContextRunner()
        .withUserConfiguration(LocalContactSeed.class)
        .withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
        .run(context -> assertThat(context).doesNotHaveBean("seedLocalContacts"));
  }
}
