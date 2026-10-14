package pe.axiz.quality.stores;

import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import pe.axiz.core.contracts.IdempotencyStoreContract;
import pe.axiz.core.contracts.InboxStoreContract;
import pe.axiz.core.idempotency.dynamodb.DynamoDbIdempotencyStore;
import pe.axiz.core.inbox.InboxStore;
import pe.axiz.core.inbox.dynamodb.DynamoDbInboxStore;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/** Idempotency and inbox contracts on DynamoDB (LocalStack): conditional writes and ALL_OLD. */
class LocalStackDynamoDbStoresIT {

  private static final LocalStackContainer LOCALSTACK =
      new LocalStackContainer(DockerImageName.parse(Docker.LOCALSTACK)).withServices("dynamodb");

  private static DynamoDbClient dynamo;

  @BeforeAll
  static void start() {
    Docker.require();
    LOCALSTACK.start();
    dynamo =
        DynamoDbClient.builder()
            .endpointOverride(LOCALSTACK.getEndpoint())
            .region(Region.of(LOCALSTACK.getRegion()))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(
                        LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
            .httpClient(UrlConnectionHttpClient.create())
            .build();
    table("axiz-idempotency");
    table("axiz-inbox");
  }

  @AfterAll
  static void stop() {
    if (dynamo != null) {
      dynamo.close();
    }
    LOCALSTACK.stop();
  }

  private static void table(String name) {
    dynamo.createTable(
        b ->
            b.tableName(name)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                    AttributeDefinition.builder()
                        .attributeName("pk")
                        .attributeType(ScalarAttributeType.S)
                        .build())
                .keySchema(
                    KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build()));
    dynamo.waiter().waitUntilTableExists(b -> b.tableName(name));
  }

  @TestFactory
  Stream<DynamicTest> idempotencyContract() {
    return IdempotencyStoreContract.checks(
            clock -> new DynamoDbIdempotencyStore(dynamo, "axiz-idempotency", clock))
        .stream()
        .map(c -> DynamicTest.dynamicTest(c.name(), c::run));
  }

  @TestFactory
  Stream<DynamicTest> inboxContract() {
    InboxStoreContract.Backend backend =
        new InboxStoreContract.Backend() {
          @Override
          public InboxStore create() {
            return new DynamoDbInboxStore(dynamo, "axiz-inbox");
          }

          @Override
          public boolean purgesOnDemand() {
            return false; // table TTL
          }
        };
    return InboxStoreContract.checks(backend).stream()
        .map(c -> DynamicTest.dynamicTest(c.name(), c::run));
  }
}
