package pe.axiz.demo.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.web.context.reactive.AnnotationConfigReactiveWebApplicationContext;
import org.springframework.boot.web.context.servlet.AnnotationConfigServletWebApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.localstack.LocalStackContainer;
import pe.axiz.core.crypto.AesGcm;
import pe.axiz.core.idempotency.AsyncIdempotencyStore;
import pe.axiz.core.idempotency.IdempotencyRecord;
import pe.axiz.core.idempotency.IdempotencyStore;
import pe.axiz.core.idempotency.StoredResponse;
import pe.axiz.core.inbox.AsyncInboxStore;
import pe.axiz.core.inbox.InboxStore;
import pe.axiz.core.lock.AsyncDistributedLock;
import pe.axiz.core.lock.DistributedLock;
import pe.axiz.core.secrets.SecretResolver;
import pe.axiz.core.secrets.SecretValue;
import pe.axiz.core.sequence.AsyncSequenceStore;
import pe.axiz.core.storage.ObjectMetadata;
import pe.axiz.core.storage.ObjectStorage;
import pe.axiz.kit.dynamodb.InvalidCursorException;
import pe.axiz.spring.aws.AxizAwsIdentityAutoConfiguration;
import pe.axiz.spring.dynamodb.AxizDynamoCursorAutoConfiguration;
import pe.axiz.spring.dynamodb.AxizDynamoDbAutoConfiguration;
import pe.axiz.spring.dynamodb.DynamoRepositories;
import pe.axiz.spring.dynamodb.ReactiveDynamoRepositories;
import pe.axiz.spring.idempotency.dynamodb.AxizIdempotencyDynamoDbAutoConfiguration;
import pe.axiz.spring.inbox.dynamodb.AxizInboxDynamoDbAutoConfiguration;
import pe.axiz.spring.lock.dynamodb.AxizLockDynamoDbAutoConfiguration;
import pe.axiz.spring.scheduling.dynamodb.AxizSchedulingDynamoDbAutoConfiguration;
import pe.axiz.spring.secrets.AxizSecretsAutoConfiguration;
import pe.axiz.spring.secrets.ReactiveSecrets;
import pe.axiz.spring.secrets.aws.AxizSecretsAwsAutoConfiguration;
import pe.axiz.spring.secrets.aws.AxizSecretsNativeAwsAutoConfiguration;
import pe.axiz.spring.sequence.AxizSequenceAutoConfiguration;
import pe.axiz.spring.sequence.ReactiveSequences;
import pe.axiz.spring.sequence.Sequences;
import pe.axiz.spring.sequence.dynamodb.AxizSequenceDynamoDbAutoConfiguration;
import pe.axiz.spring.storage.AxizStorageAutoConfiguration;
import pe.axiz.spring.storage.ReactiveStorage;
import pe.axiz.spring.storage.s3.AxizStorageS3AutoConfiguration;
import pe.axiz.spring.test.AxizContainers;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.ParameterType;

/**
 * Sin cuenta remota de AWS: la preparación y cada endpoint del SDK pertenecen a este contenedor de
 * LocalStack.
 */
class CloudRecipesTest {
  private static final Duration TIMEOUT = Duration.ofSeconds(20);
  private static final LocalStackContainer AWS = AxizContainers.localstack();
  private static final String BUCKET = "axiz-provider-recipes";
  private static final String SECRET = UUID.randomUUID().toString();

  private static AwsCredentialsProvider credentials() {
    return StaticCredentialsProvider.create(
        AwsBasicCredentials.create(AWS.getAccessKey(), AWS.getSecretKey()));
  }

  private static String[] properties() {
    return new String[] {
      "axiz.config.environment=test",
      "axiz.transport.allow-plain-http=true",
      "axiz.aws.endpoint=" + AWS.getEndpoint(),
      "axiz.aws.region=" + AWS.getRegion()
    };
  }

  @BeforeAll
  static void provision() {
    AWS.start();
    var region = Region.of(AWS.getRegion());
    try (var s3 =
            S3Client.builder()
                .region(region)
                .endpointOverride(AWS.getEndpoint())
                .credentialsProvider(credentials())
                .forcePathStyle(true)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
        var dynamo =
            DynamoDbClient.builder()
                .region(region)
                .endpointOverride(AWS.getEndpoint())
                .credentialsProvider(credentials())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
        var secrets =
            SecretsManagerClient.builder()
                .region(region)
                .endpointOverride(AWS.getEndpoint())
                .credentialsProvider(credentials())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
        var parameters =
            SsmClient.builder()
                .region(region)
                .endpointOverride(AWS.getEndpoint())
                .credentialsProvider(credentials())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build()) {
      s3.createBucket(b -> b.bucket(BUCKET));
      for (String table :
          new String[] {
            "recipe_counters", "recipe_jobs", "recipe_inbox", "recipe_idempotency", "recipe_locks"
          }) {
        String key = table.equals("recipe_jobs") ? "_id" : "pk";
        dynamo.createTable(
            b ->
                b.tableName(table)
                    .attributeDefinitions(
                        AttributeDefinition.builder()
                            .attributeName(key)
                            .attributeType(ScalarAttributeType.S)
                            .build())
                    .keySchema(
                        KeySchemaElement.builder().attributeName(key).keyType(KeyType.HASH).build())
                    .provisionedThroughput(t -> t.readCapacityUnits(5L).writeCapacityUnits(5L)));
        dynamo.waiter().waitUntilTableExists(b -> b.tableName(table));
      }
      dynamo.createTable(
          b ->
              b.tableName("recipe_documents")
                  .attributeDefinitions(
                      AttributeDefinition.builder()
                          .attributeName("pk")
                          .attributeType(ScalarAttributeType.S)
                          .build(),
                      AttributeDefinition.builder()
                          .attributeName("sk")
                          .attributeType(ScalarAttributeType.S)
                          .build())
                  .keySchema(
                      KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                      KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
                  .provisionedThroughput(t -> t.readCapacityUnits(5L).writeCapacityUnits(5L)));
      dynamo.waiter().waitUntilTableExists(b -> b.tableName("recipe_documents"));
      secrets.createSecret(b -> b.name("recipes/service-credential").secretString(SECRET));
      parameters.putParameter(
          b -> b.name("/recipes/client-key").value(SECRET).type(ParameterType.SECURE_STRING));
      parameters.putParameter(
          b ->
              b.name("/axiz/test/recipes/business-mode")
                  .value("simulation")
                  .type(ParameterType.STRING));
    }
  }

  @AfterAll
  static void cleanup() {
    AWS.stop();
  }

  @Configuration(proxyBeanMethods = false)
  static class ConfigOnly {}

  @DynamoDbBean
  public static class Document {
    private String tenant;
    private String id;

    @DynamoDbPartitionKey
    public String getPk() {
      return tenant;
    }

    public void setPk(String value) {
      tenant = value;
    }

    @DynamoDbSortKey
    public String getSk() {
      return id;
    }

    public void setSk(String value) {
      id = value;
    }

    static Document of(String tenant, String id) {
      var value = new Document();
      value.setPk(tenant);
      value.setSk(id);
      return value;
    }
  }

  @Test
  void dynamoRepositoriesPersistAndPageWithQueryBoundCursorsOnBothStacks(TestReporter evidence) {
    for (String stack : List.of("servlet", "reactive")) {
      try (var secret =
          SecretValue.of(Base64.getEncoder().encodeToString(AesGcm.newKey().getEncoded()))) {
        RecipeStacks.runner(stack)
            .withConfiguration(
                AutoConfigurations.of(
                    AxizAwsIdentityAutoConfiguration.class,
                    AxizDynamoDbAutoConfiguration.class,
                    AxizDynamoCursorAutoConfiguration.class))
            .withBean(AwsCredentialsProvider.class, CloudRecipesTest::credentials)
            .withBean(SecretResolver.class, () -> name -> Optional.of(secret))
            .withPropertyValues(properties())
            .withPropertyValues(
                "axiz.dynamodb.cursor.current-key-id=demo", "axiz.dynamodb.cursor.encrypt=true")
            .run(
                context -> {
                  assertThat(context).hasNotFailed();
                  if (stack.equals("servlet")) {
                    var repo =
                        context
                            .getBean(DynamoRepositories.class)
                            .create("recipe_documents", Document.class);
                    repo.save(Document.of(stack, "01"));
                    repo.save(Document.of(stack, "02"));
                    assertThat(repo.findById(stack, "01")).isPresent();
                    var first = repo.query(stack, 1, null);
                    assertThat(first.content()).extracting(Document::getSk).containsExactly("01");
                    assertThat(first.nextCursor()).isNotBlank().doesNotContain(stack);
                    assertThat(repo.query(stack, 1, first.nextCursor()).content())
                        .extracting(Document::getSk)
                        .containsExactly("02");
                    assertThrows(
                        InvalidCursorException.class,
                        () -> repo.query("other-tenant", 1, first.nextCursor()));
                    repo.delete(stack, "01");
                    repo.delete(stack, "02");
                    assertThat(repo.findById(stack, "01")).isEmpty();
                  } else {
                    var repo =
                        context
                            .getBean(ReactiveDynamoRepositories.class)
                            .create("recipe_documents", Document.class);
                    repo.save(Document.of(stack, "01"))
                        .then(repo.save(Document.of(stack, "02")))
                        .block(TIMEOUT);
                    assertThat(repo.findById(stack, "01").block(TIMEOUT)).isNotNull();
                    var first = repo.query(stack, 1, null).block(TIMEOUT);
                    assertThat(first.content()).extracting(Document::getSk).containsExactly("01");
                    assertThat(first.nextCursor()).isNotBlank().doesNotContain(stack);
                    assertThat(repo.query(stack, 1, first.nextCursor()).block(TIMEOUT).content())
                        .extracting(Document::getSk)
                        .containsExactly("02");
                    StepVerifier.create(repo.query("other-tenant", 1, first.nextCursor()))
                        .expectError(InvalidCursorException.class)
                        .verify();
                    repo.delete(stack, "01").then(repo.delete(stack, "02")).block(TIMEOUT);
                    StepVerifier.create(repo.findById(stack, "01")).verifyComplete();
                  }
                  evidence.publishEntry("axiz.capability.dynamodb", stack);
                  evidence.publishEntry("axiz.capability.dynamodb-cursor", stack);
                });
      }
    }
  }

  @Test
  void dynamoInboxIdempotencyAndLocksKeepOwnershipAcrossBothStacks(TestReporter evidence) {
    var now = Instant.now();
    var configurations =
        AutoConfigurations.of(
            AxizAwsIdentityAutoConfiguration.class,
            AxizInboxDynamoDbAutoConfiguration.class,
            AxizIdempotencyDynamoDbAutoConfiguration.class,
            AxizLockDynamoDbAutoConfiguration.class);
    String[] selected = {
      "axiz.inbox.store=dynamodb",
      "axiz.inbox.table=recipe_inbox",
      "axiz.idempotency.store=dynamodb",
      "axiz.idempotency.table=recipe_idempotency",
      "axiz.lock.store=dynamodb",
      "axiz.lock.table=recipe_locks"
    };
    new ApplicationContextRunner()
        .withConfiguration(configurations)
        .withBean(AwsCredentialsProvider.class, CloudRecipesTest::credentials)
        .withPropertyValues(properties())
        .withPropertyValues(selected)
        .run(
            ctx -> {
              var inbox = ctx.getBean(InboxStore.class);
              assertThat(
                      inbox
                          .tryAcquire(
                              "receipts",
                              "event-a",
                              "worker-a",
                              now,
                              now.plusSeconds(60),
                              now.plusSeconds(3600))
                          .status())
                  .isEqualTo(InboxStore.Acquisition.Status.ACQUIRED);
              assertThat(inbox.markProcessed("receipts", "event-a", "worker-b", now)).isFalse();
              assertThat(inbox.markProcessed("receipts", "event-a", "worker-a", now)).isTrue();
              var store = ctx.getBean(IdempotencyStore.class);
              var record =
                  IdempotencyRecord.builder(
                          "recipe-request-a",
                          "request-hash",
                          now.plusSeconds(60),
                          now.plusSeconds(3600))
                      .owner("worker-a")
                      .build();
              assertThat(store.tryStart(record)).isTrue();
              assertThat(store.tryStart(record)).isFalse();
              assertThat(
                      store.complete(
                          record.key(),
                          "worker-a",
                          new StoredResponse(201, Map.of(), new byte[] {1}),
                          now.plusSeconds(3600)))
                  .isTrue();
              var locks = ctx.getBean(DistributedLock.class);
              try (var lock = locks.tryAcquire("ledger-a", Duration.ofMinutes(1)).orElseThrow()) {
                assertThat(locks.tryAcquire("ledger-a", Duration.ofMinutes(1))).isEmpty();
                assertThat(lock.renew(Duration.ofMinutes(1))).isTrue();
              }
              evidence.publishEntry("axiz.capability.inbox", "servlet,dynamodb");
              evidence.publishEntry("axiz.capability.idempotency-dynamodb", "servlet,dynamodb");
              evidence.publishEntry("axiz.capability.lock-dynamodb", "servlet,dynamodb");
            });
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(configurations)
        .withBean(AwsCredentialsProvider.class, CloudRecipesTest::credentials)
        .withPropertyValues(properties())
        .withPropertyValues(selected)
        .run(
            ctx -> {
              var inbox = ctx.getBean(AsyncInboxStore.class);
              assertThat(
                      inbox
                          .tryAcquire(
                              "receipts",
                              "event-a",
                              "worker-b",
                              now,
                              now.plusSeconds(60),
                              now.plusSeconds(3600))
                          .toCompletableFuture()
                          .join()
                          .status())
                  .isEqualTo(InboxStore.Acquisition.Status.PROCESSED);
              assertThat(
                      ctx.getBean(AsyncIdempotencyStore.class)
                          .find("recipe-request-a", now)
                          .toCompletableFuture()
                          .join()
                          .orElseThrow()
                          .status())
                  .isEqualTo(IdempotencyRecord.Status.COMPLETED);
              var locks = ctx.getBean(AsyncDistributedLock.class);
              var lock =
                  locks
                      .tryAcquire("ledger-a", Duration.ofMinutes(1))
                      .toCompletableFuture()
                      .join()
                      .orElseThrow();
              try {
                assertThat(
                        locks
                            .tryAcquire("ledger-a", Duration.ofMinutes(1))
                            .toCompletableFuture()
                            .join())
                    .isEmpty();
              } finally {
                lock.release().toCompletableFuture().join();
              }
              evidence.publishEntry("axiz.capability.inbox", "reactive,dynamodb");
              evidence.publishEntry("axiz.capability.idempotency-dynamodb", "reactive,dynamodb");
              evidence.publishEntry("axiz.capability.lock-dynamodb", "reactive,dynamodb");
            });
  }

  @Test
  void parameterStoreBootstrapLoadsApplicationConfigurationBeforeBeansExist(TestReporter evidence) {
    for (String stack : List.of("servlet", "reactive")) {
      var application = new SpringApplication(ConfigOnly.class);
      application.setWebApplicationType(
          stack.equals("reactive") ? WebApplicationType.REACTIVE : WebApplicationType.SERVLET);
      application.setApplicationContextFactory(
          type ->
              type == WebApplicationType.REACTIVE
                  ? new AnnotationConfigReactiveWebApplicationContext()
                  : new AnnotationConfigServletWebApplicationContext());
      application.addBootstrapRegistryInitializer(
          registry -> registry.register(AwsCredentialsProvider.class, ignored -> credentials()));
      try (var context =
          application.run(
              "--spring.application.name=recipes",
              "--axiz.config.environment=test",
              "--axiz.config.parameter-store.application=recipes",
              "--axiz.config.parameter-store.enabled=true",
              "--axiz.transport.allow-plain-http=true",
              "--spring.cloud.aws.region.static=" + AWS.getRegion(),
              "--spring.cloud.aws.parameterstore.endpoint=" + AWS.getEndpoint())) {
        assertThat(context.getEnvironment().getRequiredProperty("business-mode"))
            .isEqualTo("simulation");
      }

      evidence.publishEntry("axiz.capability.config", stack);
      evidence.publishEntry("axiz.capability.config-aws", stack);
    }
  }

  @Test
  void s3SyncAndNativeStreamingRoundTripAndDelete(TestReporter evidence) {
    var configurations =
        AutoConfigurations.of(
            AxizAwsIdentityAutoConfiguration.class,
            AxizStorageAutoConfiguration.class,
            AxizStorageS3AutoConfiguration.class);
    byte[] content = "synthetic settlement,100.00".getBytes(StandardCharsets.UTF_8);
    new ApplicationContextRunner()
        .withConfiguration(configurations)
        .withBean(AwsCredentialsProvider.class, CloudRecipesTest::credentials)
        .withPropertyValues(properties())
        .withPropertyValues("axiz.storage.type=s3", "axiz.storage.bucket=" + BUCKET)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var storage = context.getBean(ObjectStorage.class);
              storage.put(
                  "sync/report.csv",
                  new ByteArrayInputStream(content),
                  ObjectMetadata.of("text/csv", content.length));
              try (var stored = storage.get("sync/report.csv").orElseThrow()) {
                assertThat(stored.content().readAllBytes()).isEqualTo(content);
              }
              assertThat(storage.list("sync/", 10)).containsExactly("sync/report.csv");
              storage.delete("sync/report.csv");
              assertThat(storage.head("sync/report.csv")).isEmpty();
              evidence.publishEntry("axiz.capability.storage", "servlet");
              evidence.publishEntry("axiz.capability.storage-s3", "servlet");
            });
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(configurations)
        .withBean(AwsCredentialsProvider.class, CloudRecipesTest::credentials)
        .withPropertyValues(properties())
        .withPropertyValues("axiz.storage.type=s3", "axiz.storage.bucket=" + BUCKET)
        .run(
            context -> {
              assertThat(context).hasNotFailed().doesNotHaveBean(ObjectStorage.class);
              var storage = context.getBean(ReactiveStorage.class);
              StepVerifier.create(
                      storage
                          .put(
                              "async/report.csv",
                              Flux.defer(() -> Flux.just(ByteBuffer.wrap(content))),
                              ObjectMetadata.of("text/csv", content.length))
                          .subscribeOn(Schedulers.parallel()))
                  .verifyComplete();
              StepVerifier.create(
                      storage
                          .content("async/report.csv")
                          .map(
                              buffer -> {
                                byte[] bytes = new byte[buffer.remaining()];
                                buffer.get(bytes);
                                return bytes;
                              })
                          .subscribeOn(Schedulers.parallel()))
                  .assertNext(bytes -> assertThat(bytes).isEqualTo(content))
                  .verifyComplete();
              assertThat(
                      storage.presignGet("async/report.csv", Duration.ofMinutes(1)).block(TIMEOUT))
                  .isNotNull();
              storage.delete("async/report.csv").block(TIMEOUT);
              StepVerifier.create(storage.head("async/report.csv")).verifyComplete();
              evidence.publishEntry("axiz.capability.storage", "reactive");
              evidence.publishEntry("axiz.capability.storage-s3", "reactive");
            });
  }

  @Test
  void dynamoSequencesShareCounterAcrossImperativeAndNativeConsumers(TestReporter evidence) {
    var configurations =
        AutoConfigurations.of(
            AxizAwsIdentityAutoConfiguration.class,
            AxizSequenceAutoConfiguration.class,
            AxizSequenceDynamoDbAutoConfiguration.class);
    String[] sequence = {
      "axiz.sequence.store=dynamodb",
      "axiz.sequence.table=recipe_counters",
      "axiz.sequence.sequences.receipt.prefix=RCP"
    };
    new ApplicationContextRunner()
        .withConfiguration(configurations)
        .withBean(AwsCredentialsProvider.class, CloudRecipesTest::credentials)
        .withPropertyValues(properties())
        .withPropertyValues(sequence)
        .run(
            context -> {
              assertThat(context).hasNotFailed().doesNotHaveBean(AsyncSequenceStore.class);
              assertThat(context.getBean(Sequences.class).next("receipt")).isEqualTo("RCP00000001");
              evidence.publishEntry("axiz.capability.sequence", "dynamodb,servlet");
            });
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(configurations)
        .withBean(AwsCredentialsProvider.class, CloudRecipesTest::credentials)
        .withPropertyValues(properties())
        .withPropertyValues(sequence)
        .run(
            context -> {
              assertThat(context).hasNotFailed().doesNotHaveBean(Sequences.class);
              StepVerifier.create(
                      context
                          .getBean(ReactiveSequences.class)
                          .next("receipt")
                          .subscribeOn(Schedulers.parallel()))
                  .expectNext("RCP00000002")
                  .verifyComplete();
              evidence.publishEntry("axiz.capability.sequence", "dynamodb,reactive");
            });

    evidence.publishEntry("axiz.capability.sequence", "dynamodb");
  }

  @Test
  void dynamoSchedulerExcludesCompetingWorkers(TestReporter evidence) {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizAwsIdentityAutoConfiguration.class,
                AxizSchedulingDynamoDbAutoConfiguration.class))
        .withBean(AwsCredentialsProvider.class, CloudRecipesTest::credentials)
        .withPropertyValues(properties())
        .withPropertyValues("axiz.scheduling.store=dynamodb", "axiz.scheduling.table=recipe_jobs")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              RelationalRecipesTest.lockContract(
                  context.getBean(LockProvider.class), "closing-job");
            });

    evidence.publishEntry("axiz.capability.scheduling", "dynamodb");
  }

  @Test
  void secretsManagerAndParameterStoreSupportBothStacksAndCloseValues(TestReporter evidence) {
    var configurations =
        AutoConfigurations.of(
            AxizAwsIdentityAutoConfiguration.class,
            AxizSecretsAutoConfiguration.class,
            AxizSecretsAwsAutoConfiguration.class,
            AxizSecretsNativeAwsAutoConfiguration.class);
    String[] secrets = {
      "axiz.secrets.secrets-manager.enabled=true",
      "axiz.secrets.secrets-manager.prefix=recipes/",
      "axiz.secrets.parameter-store.enabled=true",
      "axiz.secrets.parameter-store.path=/recipes/"
    };
    new ApplicationContextRunner()
        .withConfiguration(configurations)
        .withBean(AwsCredentialsProvider.class, CloudRecipesTest::credentials)
        .withPropertyValues(properties())
        .withPropertyValues(secrets)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              for (String name : new String[] {"service-credential", "client-key"}) {
                var value = context.getBean(SecretResolver.class).require(name);
                try (value) {
                  assertThat(value.reveal()).isEqualTo(SECRET);
                }
                assertThrows(IllegalStateException.class, value::reveal);
                String provider =
                    name.equals("service-credential") ? "secrets-manager" : "parameter-store";
                evidence.publishEntry("axiz.capability.secrets", "servlet," + provider);
                evidence.publishEntry("axiz.capability.secrets-aws", "servlet," + provider);
              }
            });
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(configurations)
        .withBean(AwsCredentialsProvider.class, CloudRecipesTest::credentials)
        .withPropertyValues(properties())
        .withPropertyValues(secrets)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              for (String name : new String[] {"service-credential", "client-key"}) {
                StepVerifier.create(
                        context
                            .getBean(ReactiveSecrets.class)
                            .require(name)
                            .subscribeOn(Schedulers.parallel()))
                    .assertNext(
                        value -> {
                          try (value) {
                            assertThat(value.reveal()).isEqualTo(SECRET);
                          }
                        })
                    .verifyComplete();
                String provider =
                    name.equals("service-credential") ? "secrets-manager" : "parameter-store";
                evidence.publishEntry("axiz.capability.secrets", "reactive," + provider);
                evidence.publishEntry("axiz.capability.secrets-aws", "reactive," + provider);
              }
            });
  }
}
