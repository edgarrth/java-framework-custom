package pe.axiz.quality.stores;

import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import pe.axiz.adapter.idempotency.dynamodb.DynamoDbIdempotencyStore;
import pe.axiz.adapter.inbox.dynamodb.DynamoDbInboxStore;
import pe.axiz.core.inbox.InboxStore;
import pe.axiz.test.contracts.IdempotencyStoreContract;
import pe.axiz.test.contracts.InboxStoreContract;
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
@org.junit.jupiter.api.extension.ExtendWith(pe.axiz.spring.test.BlockHoundExtension.class)
class LocalStackDynamoDbStoresIT {

  private static final LocalStackContainer LOCALSTACK =
      new LocalStackContainer(DockerImageName.parse(Docker.LOCALSTACK)).withServices("dynamodb");

  private static DynamoDbClient dynamo;
  private static pe.axiz.adapter.observability.aws.AsyncAwsClients inboxResources;
  private static software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient nativeDynamo;
  private static NettyLifecycleProbe inboxLifecycle;

  @BeforeAll
  static void start() {
    Docker.require();
    pe.axiz.spring.foundation.ReactiveInfrastructureBootstrap.initialize();
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
    table("axiz-sequence");
    inboxLifecycle = new NettyLifecycleProbe();
    inboxResources =
        new pe.axiz.adapter.observability.aws.AsyncAwsClients(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())),
            java.time.Clock.systemUTC(),
            java.time.Duration.ofSeconds(1));
    nativeDynamo =
        inboxResources.configured(
            software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient.builder(),
            io.micrometer.observation.ObservationRegistry.NOOP,
            key ->
                switch (key) {
                  case "axiz.aws.region" -> LOCALSTACK.getRegion();
                  case "axiz.aws.endpoint" -> LOCALSTACK.getEndpoint().toString();
                  default -> null;
                },
            "dynamodb");
  }

  @AfterAll
  static void stop() {
    if (nativeDynamo != null) nativeDynamo.close();
    if (inboxResources != null) inboxResources.close();
    if (dynamo != null) {
      dynamo.close();
    }
    LOCALSTACK.stop();
    if (inboxLifecycle != null) {
      inboxLifecycle.close();
      org.junit.jupiter.api.Assertions.assertTrue(
          inboxLifecycle.errors.isEmpty(), inboxLifecycle.errors.toString());
    }
    org.junit.jupiter.api.Assertions.assertTrue(
        pe.axiz.spring.test.BlockHoundExtension.detectedBlockingCalls().isEmpty(),
        () -> pe.axiz.spring.test.BlockHoundExtension.detectedBlockingCalls().toString());
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

  @TestFactory
  Stream<DynamicTest> nativeInboxContract() {
    var backend =
        new pe.axiz.test.contracts.AsyncInboxStoreContract.Backend() {
          public pe.axiz.core.inbox.AsyncInboxStore create() {
            return new pe.axiz.adapter.inbox.dynamodb.DynamoDbAsyncInboxStore(
                nativeDynamo, "axiz-inbox");
          }

          public boolean purgesOnDemand() {
            return false;
          }
        };
    return pe.axiz.test.contracts.AsyncInboxStoreContract.checks(
            backend, command -> reactor.core.scheduler.Schedulers.parallel().schedule(command))
        .stream()
        .map(check -> DynamicTest.dynamicTest("native: " + check.name(), check::run));
  }

  @Test
  void nativeSequencesReserveSharedRecordsWithoutBlockingDuringCredentialRefresh() {
    pe.axiz.spring.foundation.ReactiveInfrastructureBootstrap.initialize();
    var refreshes = new java.util.concurrent.atomic.AtomicInteger();
    java.util.Set<String> providerThreads = java.util.concurrent.ConcurrentHashMap.newKeySet();
    software.amazon.awssdk.auth.credentials.AwsCredentialsProvider credentials =
        () -> {
          providerThreads.add(Thread.currentThread().getName());
          refreshes.incrementAndGet();
          java.util.concurrent.locks.LockSupport.parkNanos(
              java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(2));
          return software.amazon.awssdk.auth.credentials.AwsSessionCredentials.builder()
              .accessKeyId(LOCALSTACK.getAccessKey())
              .secretAccessKey(LOCALSTACK.getSecretKey())
              .sessionToken("test")
              .expirationTime(java.time.Instant.now().plusSeconds(30))
              .build();
        };
    var lifecycle = new NettyLifecycleProbe();
    try (var resources =
            new pe.axiz.adapter.observability.aws.AsyncAwsClients(
                credentials, java.time.Clock.systemUTC(), java.time.Duration.ofMillis(25));
        var async =
            resources.configured(
                software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient.builder(),
                io.micrometer.observation.ObservationRegistry.NOOP,
                key ->
                    switch (key) {
                      case "axiz.aws.region" -> LOCALSTACK.getRegion();
                      case "axiz.aws.endpoint" -> LOCALSTACK.getEndpoint().toString();
                      case "axiz.aws.async.connection-ttl", "axiz.aws.async.max-idle-time" ->
                          "100ms";
                      default -> null;
                    },
                "dynamodb")) {
      var store =
          new pe.axiz.adapter.sequence.dynamodb.DynamoDbAsyncSequenceStore(async, "axiz-sequence");
      String name = "native-" + java.util.UUID.randomUUID();
      var format =
          new pe.axiz.core.sequence.SequenceFormat(
              "", 8, pe.axiz.core.sequence.ResetPolicy.NEVER, "-", java.time.ZoneOffset.UTC);
      var first =
          new pe.axiz.core.sequence.AsyncSequenceGenerator(
              name, format, store, 5, java.time.Clock.systemUTC());
      var second =
          new pe.axiz.core.sequence.AsyncSequenceGenerator(
              name, format, store, 5, java.time.Clock.systemUTC());
      var results =
          reactor.core.publisher.Flux.range(0, 100)
              .flatMap(
                  index ->
                      reactor.core.publisher.Mono.fromCompletionStage(
                          () -> (index % 2 == 0 ? first : second).nextValue()),
                  16)
              .collectList()
              .subscribeOn(reactor.core.scheduler.Schedulers.parallel());
      var greatest = new java.util.concurrent.atomic.AtomicLong();
      reactor.test.StepVerifier.create(results)
          .assertNext(
              values -> {
                org.junit.jupiter.api.Assertions.assertEquals(
                    100, new java.util.HashSet<>(values).size());
                org.junit.jupiter.api.Assertions.assertTrue(
                    values.stream().allMatch(value -> value > 0));
                greatest.set(values.stream().mapToLong(Long::longValue).max().orElseThrow());
              })
          .expectComplete()
          .verify(java.time.Duration.ofSeconds(20));
      long last =
          new pe.axiz.adapter.sequence.dynamodb.DynamoDbSequenceStore(dynamo, "axiz-sequence")
              .increment(name, "", 1);
      org.junit.jupiter.api.Assertions.assertTrue(last > greatest.get());
      // Force expiry of the short-lived native pool before allocating again.
      java.util.concurrent.locks.LockSupport.parkNanos(
          java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(150));
      reactor.test.StepVerifier.create(
              reactor.core.publisher.Mono.fromCompletionStage(() -> store.increment(name, "", 1))
                  .subscribeOn(reactor.core.scheduler.Schedulers.parallel()))
          .expectNext(last + 1)
          .verifyComplete();
      org.awaitility.Awaitility.await()
          .atMost(java.time.Duration.ofSeconds(3))
          .until(() -> refreshes.get() > 1);
      org.junit.jupiter.api.Assertions.assertTrue(
          providerThreads.stream()
              .noneMatch(
                  thread ->
                      thread.startsWith("parallel-")
                          || thread.startsWith("axiz-aws-future-")
                          || thread.startsWith("aws-java-sdk-NettyEventLoop")));
      var missing =
          new pe.axiz.adapter.sequence.dynamodb.DynamoDbAsyncSequenceStore(
              async, "nonexistent-" + java.util.UUID.randomUUID());
      reactor.test.StepVerifier.create(
              reactor.core.publisher.Mono.fromCompletionStage(() -> missing.increment(name, "", 1))
                  .subscribeOn(reactor.core.scheduler.Schedulers.parallel()))
          .expectError(
              software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException.class)
          .verify(java.time.Duration.ofSeconds(10));
    } finally {
      lifecycle.close();
    }
    org.junit.jupiter.api.Assertions.assertTrue(
        lifecycle.errors.isEmpty(), () -> "SDK lifecycle errors: " + lifecycle.errors);
  }
}
