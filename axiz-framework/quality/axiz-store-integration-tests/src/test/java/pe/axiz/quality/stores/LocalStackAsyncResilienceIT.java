package pe.axiz.quality.stores;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import pe.axiz.adapter.audit.sqs.SqsAsyncAuditPublisher;
import pe.axiz.adapter.aws.transport.AsyncAwsClients;
import pe.axiz.adapter.crypto.kms.KmsAsyncDataKeyProvider;
import pe.axiz.adapter.idempotency.dynamodb.DynamoDbAsyncIdempotencyStore;
import pe.axiz.adapter.inbox.dynamodb.DynamoDbAsyncInboxStore;
import pe.axiz.adapter.lock.dynamodb.DynamoDbAsyncDistributedLock;
import pe.axiz.adapter.messaging.sns.SnsAsyncEventPublisher;
import pe.axiz.adapter.messaging.sqs.SqsAsyncEventPublisher;
import pe.axiz.adapter.observability.aws.AwsObservability;
import pe.axiz.adapter.secrets.aws.ParameterStoreAsyncResolver;
import pe.axiz.adapter.secrets.aws.SecretsManagerAsyncResolver;
import pe.axiz.adapter.sequence.dynamodb.DynamoDbAsyncSequenceStore;
import pe.axiz.adapter.storage.s3.S3AsyncObjectStorage;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.commons.TransactionOutcome;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.spring.foundation.ReactiveInfrastructureBootstrap;
import pe.axiz.spring.test.BlockHoundExtension;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.kms.KmsAsyncClient;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerAsyncClient;
import software.amazon.awssdk.services.sns.SnsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.ssm.SsmAsyncClient;
import software.amazon.awssdk.services.ssm.model.ParameterType;
import software.amazon.awssdk.utils.SdkAutoCloseable;

/** Cancellation and real SDK deadlines through public ports, retaining the framework transport. */
@ExtendWith(BlockHoundExtension.class)
class LocalStackAsyncResilienceIT {
  private static final Duration T = Duration.ofSeconds(80);
  private static final LocalStackContainer LOCALSTACK =
      new LocalStackContainer(DockerImageName.parse(Docker.LOCALSTACK))
          .withServices("dynamodb", "sqs", "sns", "s3", "kms", "secretsmanager", "ssm");
  private static final List<SdkAutoCloseable> CLIENTS = new ArrayList<>();
  private static final List<Operation> OPERATIONS = new ArrayList<>();
  private static AsyncAwsClients resources;

  record Operation(String name, Supplier<? extends CompletionStage<?>> work) {}

  @BeforeAll
  static void start() throws Exception {
    Docker.require();
    ReactiveInfrastructureBootstrap.initialize();
    LOCALSTACK.start();
    resources =
        new AsyncAwsClients(
            pe.axiz.adapter.aws.identity.AwsCredentialSource.borrowed(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(
                        LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey()))),
            Clock.systemUTC(),
            Duration.ofSeconds(1));
    var dynamo =
        own(
            AwsObservability.configured(
                resources,
                DynamoDbAsyncClient.builder(),
                ObservationRegistry.NOOP,
                LocalStackAsyncResilienceIT::property,
                "dynamodb"));
    for (String name : List.of("r8-idem", "r8-inbox", "r8-lock", "r8-sequence")) {
      dynamo
          .createTable(
              b ->
                  b.tableName(name)
                      .billingMode(BillingMode.PAY_PER_REQUEST)
                      .attributeDefinitions(
                          a -> a.attributeName("pk").attributeType(ScalarAttributeType.S))
                      .keySchema(k -> k.attributeName("pk").keyType(KeyType.HASH)))
          .get(20, TimeUnit.SECONDS);
    }
    var sqs =
        own(
            AwsObservability.configured(
                resources,
                SqsAsyncClient.builder(),
                ObservationRegistry.NOOP,
                LocalStackAsyncResilienceIT::property,
                "sqs"));
    String queue =
        sqs.createQueue(b -> b.queueName("r8-resilience")).get(20, TimeUnit.SECONDS).queueUrl();
    var sns =
        own(
            AwsObservability.configured(
                resources,
                SnsAsyncClient.builder(),
                ObservationRegistry.NOOP,
                LocalStackAsyncResilienceIT::property,
                "sns"));
    String topic =
        sns.createTopic(b -> b.name("r8-resilience")).get(20, TimeUnit.SECONDS).topicArn();
    var kms =
        own(
            AwsObservability.configured(
                resources,
                KmsAsyncClient.builder(),
                ObservationRegistry.NOOP,
                LocalStackAsyncResilienceIT::property,
                "kms"));
    String key = kms.createKey(b -> {}).get(20, TimeUnit.SECONDS).keyMetadata().keyId();
    var sm =
        own(
            AwsObservability.configured(
                resources,
                SecretsManagerAsyncClient.builder(),
                ObservationRegistry.NOOP,
                LocalStackAsyncResilienceIT::property,
                "secretsmanager"));
    sm.createSecret(b -> b.name("r8-token").secretString("test-value")).get(20, TimeUnit.SECONDS);
    var ssm =
        own(
            AwsObservability.configured(
                resources,
                SsmAsyncClient.builder(),
                ObservationRegistry.NOOP,
                LocalStackAsyncResilienceIT::property,
                "ssm"));
    ssm.putParameter(b -> b.name("/r8/token").value("test-value").type(ParameterType.STRING))
        .get(20, TimeUnit.SECONDS);
    var s3 =
        own(
            AwsObservability.configured(
                resources,
                S3AsyncClient.builder().forcePathStyle(true),
                ObservationRegistry.NOOP,
                LocalStackAsyncResilienceIT::property,
                "s3"));
    s3.createBucket(b -> b.bucket("r8-resilience")).get(20, TimeUnit.SECONDS);
    var presigner =
        own(
            S3Presigner.builder()
                .region(Region.of(LOCALSTACK.getRegion()))
                .endpointOverride(LOCALSTACK.getEndpoint())
                .credentialsProvider(resources.credentialsProvider())
                .build());
    var storage =
        new S3AsyncObjectStorage(s3, presigner, "r8-resilience", null, Duration.ofMinutes(5));
    var idem = new DynamoDbAsyncIdempotencyStore(dynamo, "r8-idem", Clock.systemUTC());
    var inbox = new DynamoDbAsyncInboxStore(dynamo, "r8-inbox");
    var locks = new DynamoDbAsyncDistributedLock(dynamo, "r8-lock", Clock.systemUTC());
    var sequences = new DynamoDbAsyncSequenceStore(dynamo, "r8-sequence");
    var audit = new SqsAsyncAuditPublisher(sqs, queue);
    OPERATIONS.add(
        new Operation("dynamodb-idempotency", () -> idem.find("missing", Instant.now())));
    OPERATIONS.add(
        new Operation(
            "dynamodb-inbox",
            () ->
                inbox.tryAcquire(
                    "consumer",
                    UUID.randomUUID().toString(),
                    "owner",
                    Instant.now(),
                    Instant.now().plusSeconds(30),
                    Instant.now().plusSeconds(60))));
    OPERATIONS.add(
        new Operation(
            "dynamodb-lock",
            () -> locks.tryAcquire(UUID.randomUUID().toString(), Duration.ofSeconds(30))));
    OPERATIONS.add(
        new Operation("dynamodb-sequence", () -> sequences.increment("sequence", "period", 1)));
    OPERATIONS.add(
        new Operation("sqs-events", () -> new SqsAsyncEventPublisher(sqs).publish(queue, event())));
    OPERATIONS.add(
        new Operation("sns-events", () -> new SnsAsyncEventPublisher(sns).publish(topic, event())));
    OPERATIONS.add(
        new Operation(
            "sqs-audit",
            () ->
                audit.publish(
                    new AuditEvent(
                        UUID.randomUUID().toString(),
                        Instant.now(),
                        "svc",
                        "pay",
                        "account",
                        "a-1",
                        AuditEvent.Actor.system(),
                        AuditEvent.Outcome.SUCCESS,
                        null,
                        "correlation",
                        "test",
                        null,
                        Map.of(),
                        TransactionOutcome.UNKNOWN,
                        TransactionOutcome.UNKNOWN))));
    OPERATIONS.add(
        new Operation("kms-key", () -> new KmsAsyncDataKeyProvider(kms, key).generate("context")));
    OPERATIONS.add(
        new Operation(
            "secretsmanager", () -> new SecretsManagerAsyncResolver(sm, "").resolve("r8-token")));
    OPERATIONS.add(
        new Operation(
            "parameter-store", () -> new ParameterStoreAsyncResolver(ssm, "/r8").resolve("token")));
    OPERATIONS.add(new Operation("s3", () -> storage.head("missing")));
  }

  private static String property(String key) {
    return switch (key) {
      case "axiz.aws.region" -> LOCALSTACK.getRegion();
      case "axiz.aws.endpoint" -> LOCALSTACK.getEndpoint().toString();
      default -> null;
    };
  }

  private static <T extends SdkAutoCloseable> T own(T client) {
    CLIENTS.add(client);
    return client;
  }

  private static EventEnvelope event() {
    return new EventEnvelope(
        UUID.randomUUID().toString(),
        "urn:test",
        "payment.v1",
        Instant.now(),
        "account",
        "application/json",
        new byte[] {123, 125},
        Map.of());
  }

  @TestFactory
  List<DynamicTest> cancellationAndDeadline() {
    // Freeze one real backend, send all operations concurrently and wait for their configured
    // 60s API deadlines together. The test keeps the actual production timeout configuration.
    return List.of(
        DynamicTest.dynamicTest(
            "every port cancels and reaches its SDK deadline",
            () -> {
              for (var operation : OPERATIONS) {
                StepVerifier.create(
                        Mono.fromCompletionStage(operation.work())
                            .subscribeOn(Schedulers.parallel())
                            .then())
                    .expectComplete()
                    .verify(Duration.ofSeconds(20));
              }
              LOCALSTACK.getDockerClient().pauseContainerCmd(LOCALSTACK.getContainerId()).exec();
              try {
                for (var operation : OPERATIONS) verifyCancellation(operation);
                var failures =
                    OPERATIONS.stream()
                        .map(
                            operation ->
                                Mono.fromCompletionStage(operation.work())
                                    .subscribeOn(Schedulers.parallel())
                                    .then()
                                    .materialize()
                                    .doOnNext(
                                        signal ->
                                            assertTrue(
                                                signal.isOnError()
                                                    && isDeadline(signal.getThrowable()),
                                                () ->
                                                    operation.name()
                                                        + " expected SDK timeout, got "
                                                        + signal))
                                    .toFuture())
                        .toList();
                CompletableFuture.allOf(failures.toArray(CompletableFuture[]::new))
                    .get(80, TimeUnit.SECONDS);
                assertTrue(BlockHoundExtension.detectedBlockingCalls().isEmpty());
              } finally {
                LOCALSTACK
                    .getDockerClient()
                    .unpauseContainerCmd(LOCALSTACK.getContainerId())
                    .exec();
              }
              for (var operation : OPERATIONS) {
                StepVerifier.create(
                        Mono.fromCompletionStage(operation.work())
                            .subscribeOn(Schedulers.parallel())
                            .then())
                    .expectComplete()
                    .verify(Duration.ofSeconds(20));
              }
            }));
  }

  private static void verifyCancellation(Operation operation) throws Exception {
    var submitted = new CountDownLatch(1);
    var source = new AtomicReference<CompletableFuture<?>>();
    var result =
        Mono.fromCompletionStage(
                () -> {
                  var stage = operation.work().get();
                  source.set(stage.toCompletableFuture());
                  submitted.countDown();
                  return stage;
                })
            .subscribeOn(Schedulers.parallel())
            .toFuture();
    assertTrue(submitted.await(3, TimeUnit.SECONDS), operation.name());
    result.cancel(false);
    Awaitility.await().atMost(Duration.ofSeconds(3)).until(() -> source.get().isCancelled());
  }

  private static boolean isDeadline(Throwable error) {
    for (Throwable current = error; current != null; current = current.getCause())
      if (current instanceof ApiCallTimeoutException
          || current instanceof ApiCallAttemptTimeoutException) return true;
    return false;
  }

  @AfterAll
  static void close() {
    try {
      CLIENTS.forEach(SdkAutoCloseable::close);
    } finally {
      if (resources != null) resources.close();
      LOCALSTACK.stop();
    }
    assertTrue(
        BlockHoundExtension.detectedBlockingCalls().isEmpty(),
        () -> BlockHoundExtension.detectedBlockingCalls().toString());
  }
}
