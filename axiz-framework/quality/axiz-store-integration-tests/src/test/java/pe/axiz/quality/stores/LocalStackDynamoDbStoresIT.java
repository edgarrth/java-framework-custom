package pe.axiz.quality.stores;

import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.localstack.LocalStackContainer;
import pe.axiz.adapter.aws.transport.AsyncAwsClients;
import pe.axiz.adapter.aws.transport.AsyncAwsTransportSettings;
import pe.axiz.adapter.idempotency.dynamodb.DynamoDbAsyncIdempotencyStore;
import pe.axiz.adapter.idempotency.dynamodb.DynamoDbIdempotencyStore;
import pe.axiz.adapter.inbox.dynamodb.DynamoDbAsyncInboxStore;
import pe.axiz.adapter.inbox.dynamodb.DynamoDbInboxStore;
import pe.axiz.adapter.lock.dynamodb.DynamoDbAsyncDistributedLock;
import pe.axiz.adapter.observability.aws.AwsObservability;
import pe.axiz.adapter.sequence.dynamodb.DynamoDbAsyncSequenceStore;
import pe.axiz.adapter.sequence.dynamodb.DynamoDbSequenceStore;
import pe.axiz.core.idempotency.IdempotencyRecord;
import pe.axiz.core.idempotency.IdempotencyRecord.Status;
import pe.axiz.core.idempotency.StoredResponse;
import pe.axiz.core.inbox.AsyncInboxStore;
import pe.axiz.core.inbox.InboxStore;
import pe.axiz.core.sequence.AsyncSequenceGenerator;
import pe.axiz.core.sequence.ResetPolicy;
import pe.axiz.core.sequence.SequenceFormat;
import pe.axiz.quality.containers.TestContainerImages;
import pe.axiz.spring.foundation.ReactiveInfrastructureBootstrap;
import pe.axiz.spring.test.BlockHoundExtension;
import pe.axiz.test.contracts.AsyncInboxStoreContract;
import pe.axiz.test.contracts.AsyncInboxStoreContract.Backend;
import pe.axiz.test.contracts.IdempotencyStoreContract;
import pe.axiz.test.contracts.InboxStoreContract;
import pe.axiz.test.support.MutableClock;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/**
 * Contratos de idempotencia e inbox sobre DynamoDB (LocalStack): escrituras condicionales y
 * ALL_OLD.
 */
@ExtendWith(BlockHoundExtension.class)
class LocalStackDynamoDbStoresIT {

  private static final LocalStackContainer LOCALSTACK =
      TestContainerImages.localstack().withServices("dynamodb");

  private static DynamoDbClient dynamo;
  private static AsyncAwsClients inboxResources;
  private static DynamoDbAsyncClient nativeDynamo;
  private static NettyLifecycleProbe inboxLifecycle;

  @BeforeAll
  static void start() {
    Docker.require();
    ReactiveInfrastructureBootstrap.initialize();
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
    table("axiz-locks");
    inboxLifecycle = new NettyLifecycleProbe();
    inboxResources =
        new AsyncAwsClients(
            pe.axiz.adapter.aws.identity.AwsCredentialSource.borrowed(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(
                        LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey()))),
            Clock.systemUTC(),
            Duration.ofSeconds(1),
            AsyncAwsTransportSettings.defaults());
    nativeDynamo =
        AwsObservability.configured(
            inboxResources,
            DynamoDbAsyncClient.builder(),
            ObservationRegistry.NOOP,
            key ->
                switch (key) {
                  case "axiz.aws.region" -> LOCALSTACK.getRegion();
                  case "axiz.config.environment" -> "test";
                  case "axiz.transport.allow-plain-http" -> "true";
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
      Assertions.assertTrue(inboxLifecycle.errors.isEmpty(), inboxLifecycle.errors.toString());
    }
    Assertions.assertTrue(
        BlockHoundExtension.detectedBlockingCalls().isEmpty(),
        () -> BlockHoundExtension.detectedBlockingCalls().toString());
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

  @Test
  void protectedReplayAndConcurrentCasKeepTheSameCiphertext() {
    ProtectedReplayChecks.verify(
        new DynamoDbIdempotencyStore(dynamo, "axiz-idempotency"),
        new DynamoDbAsyncIdempotencyStore(nativeDynamo, "axiz-idempotency", Clock.systemUTC()),
        key ->
            dynamo
                .getItem(
                    b ->
                        b.tableName("axiz-idempotency")
                            .key(Map.of("pk", AttributeValue.fromS(key)))
                            .consistentRead(true))
                .item()
                .get("data")
                .s());
  }

  @TestFactory
  Stream<DynamicTest> idempotencyContract() {
    return IdempotencyStoreContract.checks(
            clock -> new DynamoDbIdempotencyStore(dynamo, "axiz-idempotency", clock))
        .stream()
        .map(c -> DynamicTest.dynamicTest(c.name(), c::run));
  }

  @Test
  void inboxCompletionEnablesTtlAndRejectsRowsWithoutMigratedRetention() throws Exception {
    for (boolean nativeMode : new boolean[] {false, true}) {
      String event = UUID.randomUUID().toString();
      String consumer = "ttl-contract";
      Instant now = Instant.now();
      Instant expiration = now.plusSeconds(3600);
      var sync = new DynamoDbInboxStore(dynamo, "axiz-inbox");
      var async = new DynamoDbAsyncInboxStore(nativeDynamo, "axiz-inbox");
      if (nativeMode)
        async
            .tryAcquire(consumer, event, "owner", now, now.plusSeconds(60), expiration)
            .toCompletableFuture()
            .get(15, TimeUnit.SECONDS);
      else sync.tryAcquire(consumer, event, "owner", now, now.plusSeconds(60), expiration);
      var key = Map.of("pk", AttributeValue.fromS(consumer + "#" + event));
      var acquired =
          dynamo.getItem(b -> b.tableName("axiz-inbox").key(key).consistentRead(true)).item();
      Assertions.assertFalse(acquired.containsKey("ttl"));
      dynamo.updateItem(
          b -> b.tableName("axiz-inbox").key(key).updateExpression("REMOVE expiresAt"));
      boolean rejected =
          nativeMode
              ? async
                  .markProcessed(consumer, event, "owner", now.plusSeconds(1))
                  .toCompletableFuture()
                  .get(15, TimeUnit.SECONDS)
              : sync.markProcessed(consumer, event, "owner", now.plusSeconds(1));
      Assertions.assertFalse(rejected);
      dynamo.updateItem(
          b ->
              b.tableName("axiz-inbox")
                  .key(key)
                  .updateExpression("SET expiresAt = :expiry")
                  .expressionAttributeValues(Map.of(":expiry", acquired.get("expiresAt"))));
      boolean completed =
          nativeMode
              ? async
                  .markProcessed(consumer, event, "owner", now.plusSeconds(1))
                  .toCompletableFuture()
                  .get(15, TimeUnit.SECONDS)
              : sync.markProcessed(consumer, event, "owner", now.plusSeconds(1));
      Assertions.assertTrue(completed);
      var processed =
          dynamo.getItem(b -> b.tableName("axiz-inbox").key(key).consistentRead(true)).item();
      Assertions.assertEquals(acquired.get("expiresAt"), processed.get("ttl"));
      Assertions.assertEquals("PROCESSED", processed.get("status").s());
    }
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
            return false; // TTL de la tabla
          }
        };
    return InboxStoreContract.checks(backend).stream()
        .map(c -> DynamicTest.dynamicTest(c.name(), c::run));
  }

  @TestFactory
  Stream<DynamicTest> nativeInboxContract() {
    var backend =
        new Backend() {
          public AsyncInboxStore create() {
            return new DynamoDbAsyncInboxStore(nativeDynamo, "axiz-inbox");
          }

          public boolean purgesOnDemand() {
            return false;
          }
        };
    return AsyncInboxStoreContract.checks(
            backend, command -> Schedulers.parallel().schedule(command))
        .stream()
        .map(check -> DynamicTest.dynamicTest("native: " + check.name(), check::run));
  }

  @Test
  void nativeSequencesReserveSharedRecordsWithoutBlockingDuringCredentialRefresh() {
    ReactiveInfrastructureBootstrap.initialize();
    var refreshes = new AtomicInteger();
    Set<String> providerThreads = ConcurrentHashMap.newKeySet();
    AwsCredentialsProvider credentials =
        () -> {
          providerThreads.add(Thread.currentThread().getName());
          refreshes.incrementAndGet();
          LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
          return AwsSessionCredentials.builder()
              .accessKeyId(LOCALSTACK.getAccessKey())
              .secretAccessKey(LOCALSTACK.getSecretKey())
              .sessionToken("test")
              .expirationTime(Instant.now().plusSeconds(30))
              .build();
        };
    var lifecycle = new NettyLifecycleProbe();
    try (var resources =
            new AsyncAwsClients(
                pe.axiz.adapter.aws.identity.AwsCredentialSource.borrowed(credentials),
                Clock.systemUTC(),
                Duration.ofMillis(25),
                AsyncAwsTransportSettings.defaults());
        var async =
            AwsObservability.configured(
                resources,
                DynamoDbAsyncClient.builder(),
                ObservationRegistry.NOOP,
                key ->
                    switch (key) {
                      case "axiz.aws.region" -> LOCALSTACK.getRegion();
                      case "axiz.config.environment" -> "test";
                      case "axiz.transport.allow-plain-http" -> "true";
                      case "axiz.aws.endpoint" -> LOCALSTACK.getEndpoint().toString();
                      case "axiz.aws.async.connection-ttl", "axiz.aws.async.max-idle-time" ->
                          "100ms";
                      default -> null;
                    },
                "dynamodb")) {
      var store = new DynamoDbAsyncSequenceStore(async, "axiz-sequence");
      String name = "native-" + UUID.randomUUID();
      var format = new SequenceFormat("", 8, ResetPolicy.NEVER, "-", ZoneOffset.UTC);
      var first = new AsyncSequenceGenerator(name, format, store, 5, Clock.systemUTC());
      var second = new AsyncSequenceGenerator(name, format, store, 5, Clock.systemUTC());
      var results =
          Flux.range(0, 100)
              .flatMap(
                  index ->
                      Mono.fromCompletionStage(() -> (index % 2 == 0 ? first : second).nextValue()),
                  16)
              .collectList()
              .subscribeOn(Schedulers.parallel());
      var greatest = new AtomicLong();
      StepVerifier.create(results)
          .assertNext(
              values -> {
                Assertions.assertEquals(100, new HashSet<>(values).size());
                Assertions.assertTrue(values.stream().allMatch(value -> value > 0));
                greatest.set(values.stream().mapToLong(Long::longValue).max().orElseThrow());
              })
          .expectComplete()
          .verify(Duration.ofSeconds(20));
      long last = new DynamoDbSequenceStore(dynamo, "axiz-sequence").increment(name, "", 1);
      Assertions.assertTrue(last > greatest.get());
      // Se fuerza la expiración del pool nativo de vida corta previo a asignar de nuevo.
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(150));
      StepVerifier.create(
              Mono.fromCompletionStage(() -> store.increment(name, "", 1))
                  .subscribeOn(Schedulers.parallel()))
          .expectNext(last + 1)
          .verifyComplete();
      Awaitility.await().atMost(Duration.ofSeconds(3)).until(() -> refreshes.get() > 1);
      Assertions.assertTrue(
          providerThreads.stream()
              .noneMatch(
                  thread ->
                      thread.startsWith("parallel-")
                          || thread.startsWith("axiz-aws-future-")
                          || thread.startsWith("aws-java-sdk-NettyEventLoop")));
      var missing = new DynamoDbAsyncSequenceStore(async, "nonexistent-" + UUID.randomUUID());
      StepVerifier.create(
              Mono.fromCompletionStage(() -> missing.increment(name, "", 1))
                  .subscribeOn(Schedulers.parallel()))
          .expectError(ResourceNotFoundException.class)
          .verify(Duration.ofSeconds(10));
    } finally {
      lifecycle.close();
    }
    Assertions.assertTrue(
        lifecycle.errors.isEmpty(), () -> "SDK lifecycle errors: " + lifecycle.errors);
  }

  @Test
  void nativeIdempotencyPreservesOwnershipAndFullResponseInRealDynamo() {
    var now = Instant.now();
    var store =
        new DynamoDbAsyncIdempotencyStore(nativeDynamo, "axiz-idempotency", Clock.systemUTC());
    var key = "native-" + UUID.randomUUID();
    var record =
        IdempotencyRecord.builder(key, "hash", now.plusSeconds(60), now.plusSeconds(60))
            .status(Status.IN_PROGRESS)
            .owner("owner")
            .response(null)
            .resolutionVersion(0)
            .failure(null)
            .build();
    var response = new StoredResponse(201, Map.of("Location", "/payments/1"), new byte[] {0, 127});
    StepVerifier.create(nativeCall(() -> store.tryStart(record)))
        .expectNext(true)
        .expectComplete()
        .verify(Duration.ofSeconds(10));
    StepVerifier.create(
            nativeCall(() -> store.complete(key, "wrong", response, now.plusSeconds(60))))
        .expectNext(false)
        .expectComplete()
        .verify(Duration.ofSeconds(10));
    StepVerifier.create(
            nativeCall(() -> store.complete(key, "owner", response, now.plusSeconds(60)))
                .doOnNext(completed -> Assertions.assertTrue(completed))
                .then(
                    nativeCall(
                        () ->
                            new DynamoDbAsyncIdempotencyStore(
                                    nativeDynamo, "axiz-idempotency", Clock.systemUTC())
                                .find(key, now))))
        .assertNext(found -> Assertions.assertEquals(response, found.orElseThrow().response()))
        .expectComplete()
        .verify(Duration.ofSeconds(10));
    StepVerifier.create(nativeCall(() -> store.tryStart(record)))
        .expectNext(false)
        .expectComplete()
        .verify(Duration.ofSeconds(10));
  }

  @Test
  void nativeLocksFenceExpiredOwnersOnTheActualConditionalWrites() {
    var clock = new MutableClock(Instant.now(), ZoneOffset.UTC);
    var locks = new DynamoDbAsyncDistributedLock(nativeDynamo, "axiz-locks", clock);
    StepVerifier.create(
            nativeCall(() -> locks.tryAcquire("reactive-account", Duration.ofSeconds(1)))
                .flatMap(
                    found -> {
                      var old = found.orElseThrow();
                      return nativeCall(() -> old.renew(Duration.ofSeconds(1)))
                          .doOnNext(renewed -> Assertions.assertTrue(renewed))
                          .then(
                              nativeCall(
                                  () ->
                                      locks.tryAcquire("reactive-account", Duration.ofSeconds(1))))
                          .doOnNext(busy -> Assertions.assertTrue(busy.isEmpty()))
                          .then(
                              Mono.defer(
                                  () -> {
                                    clock.advance(Duration.ofSeconds(2));
                                    return nativeCall(
                                        () ->
                                            locks.tryAcquire(
                                                "reactive-account", Duration.ofSeconds(10)));
                                  }))
                          .flatMap(
                              acquired -> {
                                var current = acquired.orElseThrow();
                                return nativeCall(() -> old.renew(Duration.ofSeconds(1)))
                                    .doOnNext(renewed -> Assertions.assertFalse(renewed))
                                    .then(nativeCall(old::release))
                                    .then(
                                        nativeCall(
                                            () ->
                                                locks.tryAcquire(
                                                    "reactive-account", Duration.ofSeconds(1))))
                                    .doOnNext(busy -> Assertions.assertTrue(busy.isEmpty()))
                                    .then(nativeCall(current::release));
                              });
                    }))
        .expectComplete()
        .verify(Duration.ofSeconds(10));
  }

  private static <T> Mono<T> nativeCall(Supplier<CompletionStage<T>> operation) {
    return Mono.fromCompletionStage(operation).subscribeOn(Schedulers.parallel());
  }
}
