package pe.axiz.quality.stores;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import pe.axiz.adapter.aws.transport.AsyncAwsClients;
import pe.axiz.adapter.aws.transport.AsyncAwsTransportSettings;
import pe.axiz.adapter.observability.aws.AwsObservability;
import pe.axiz.core.crypto.AesGcm;
import pe.axiz.core.crypto.StaticKeyProvider;
import pe.axiz.kit.dynamodb.CursorCodec;
import pe.axiz.kit.dynamodb.DynamoAsyncRepository;
import pe.axiz.spring.dynamodb.ReactiveDynamoRepository;
import pe.axiz.spring.foundation.ReactiveInfrastructureBootstrap;
import pe.axiz.spring.test.BlockHoundExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.extensions.annotations.DynamoDbVersionAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactPutItemEnhancedRequest;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/** Native repository wire contract, with cold operations and callbacks checked by BlockHound. */
@ExtendWith(BlockHoundExtension.class)
class LocalStackDynamoRepositoryIT {
  private static final SecretKey CURSOR_KEY = AesGcm.newKey();
  private static final LocalStackContainer LOCALSTACK =
      new LocalStackContainer(DockerImageName.parse(Docker.LOCALSTACK)).withServices("dynamodb");
  private static AsyncAwsClients resources;
  private static DynamoDbAsyncClient client;
  private static DynamoAsyncRepository<Item> nativeRepository;
  private static ReactiveDynamoRepository<Item> repository;
  private static NettyLifecycleProbe lifecycle;

  @DynamoDbBean
  public static final class Item {
    private String pk;
    private String sk;
    private String status;
    private Long version;

    public Item() {}

    Item(String pk, String sk, String status) {
      this.pk = pk;
      this.sk = sk;
      this.status = status;
    }

    @DynamoDbPartitionKey
    public String getPk() {
      return pk;
    }

    public void setPk(String value) {
      pk = value;
    }

    @DynamoDbSortKey
    public String getSk() {
      return sk;
    }

    public void setSk(String value) {
      sk = value;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = "by-status")
    public String getStatus() {
      return status;
    }

    public void setStatus(String value) {
      status = value;
    }

    @DynamoDbVersionAttribute
    public Long getVersion() {
      return version;
    }

    public void setVersion(Long value) {
      version = value;
    }
  }

  @BeforeAll
  static void start() {
    Docker.require();
    ReactiveInfrastructureBootstrap.initialize();
    LOCALSTACK.start();
    var credentials =
        StaticCredentialsProvider.create(
            AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey()));
    try (var setup =
        DynamoDbClient.builder()
            .endpointOverride(LOCALSTACK.getEndpoint())
            .region(Region.of(LOCALSTACK.getRegion()))
            .credentialsProvider(credentials)
            .httpClient(UrlConnectionHttpClient.create())
            .build()) {
      setup.createTable(
          b ->
              b.tableName("axiz-native-repository")
                  .billingMode(BillingMode.PAY_PER_REQUEST)
                  .attributeDefinitions(
                      a -> a.attributeName("pk").attributeType(ScalarAttributeType.S),
                      a -> a.attributeName("sk").attributeType(ScalarAttributeType.S),
                      a -> a.attributeName("status").attributeType(ScalarAttributeType.S))
                  .keySchema(
                      k -> k.attributeName("pk").keyType(KeyType.HASH),
                      k -> k.attributeName("sk").keyType(KeyType.RANGE))
                  .globalSecondaryIndexes(
                      i ->
                          i.indexName("by-status")
                              .keySchema(k -> k.attributeName("status").keyType(KeyType.HASH))
                              .projection(p -> p.projectionType(ProjectionType.ALL))));
      setup.waiter().waitUntilTableExists(b -> b.tableName("axiz-native-repository"));
    }
    lifecycle = new NettyLifecycleProbe();
    resources =
        new AsyncAwsClients(
            pe.axiz.adapter.aws.identity.AwsCredentialSource.borrowed(credentials),
            Clock.systemUTC(),
            Duration.ofMillis(25),
            AsyncAwsTransportSettings.defaults());
    client =
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
                  case "axiz.aws.async.connection-ttl", "axiz.aws.async.max-idle-time" -> "100ms";
                  default -> null;
                },
            "dynamodb");
    nativeRepository =
        new DynamoAsyncRepository<>(
            client,
            "axiz-native-repository",
            TableSchema.fromBean(Item.class),
            () ->
                new CursorCodec(new StaticKeyProvider("test", Map.of("test", CURSOR_KEY)), false));
    repository = new ReactiveDynamoRepository<>(nativeRepository);
  }

  @AfterAll
  static void stop() {
    if (client != null) client.close();
    if (resources != null) resources.close();
    LOCALSTACK.stop();
    if (lifecycle != null) {
      lifecycle.close();
      assertTrue(
          lifecycle.errors.isEmpty(), () -> "Transport lifecycle errors: " + lifecycle.errors);
    }
    assertTrue(
        BlockHoundExtension.detectedBlockingCalls().isEmpty(),
        () -> "Original blocking calls: " + BlockHoundExtension.detectedBlockingCalls());
  }

  @Test
  void coldCrudAndOptimisticConflictsUseNativeIo() {
    String pk = UUID.randomUUID().toString();
    var flow =
        repository
            .findById(pk, "1")
            .switchIfEmpty(
                repository.insert(new Item(pk, "1", pk), "pk").then(repository.findById(pk, "1")))
            .flatMap(
                item -> {
                  assertEquals(1L, item.getVersion());
                  item.setStatus("updated");
                  return repository.update(item);
                })
            .flatMap(
                updated -> {
                  assertEquals(2L, updated.getVersion());
                  var stale = new Item(pk, "1", "stale");
                  stale.setVersion(1L);
                  return repository
                      .update(stale)
                      .then(Mono.error(new AssertionError("stale write succeeded")))
                      .onErrorResume(
                          ConditionalCheckFailedException.class,
                          ignored -> repository.findById(pk, "1"));
                })
            .cast(Item.class)
            .flatMap(
                found -> {
                  assertEquals("updated", found.getStatus());
                  return repository.delete(pk, "1").then(repository.findById(pk, "1"));
                });
    StepVerifier.create(flow.subscribeOn(Schedulers.parallel())).verifyComplete();
  }

  @Test
  void boundedPagesAndIndexQueriesKeepOpaqueContinuation() {
    String pk = UUID.randomUUID().toString();
    var flow =
        Flux.range(0, 6)
            .flatMap(n -> repository.save(new Item(pk, "B-" + n, pk)), 3)
            .then(repository.queryBeginsWith(pk, "B-", 2, null))
            .flatMap(
                first -> {
                  assertEquals(2, first.content().size());
                  assertTrue(first.nextCursor() != null);
                  return repository.queryBeginsWith(pk, "B-", 2, first.nextCursor());
                })
            .flatMap(
                second -> {
                  assertEquals("B-2", second.content().getFirst().getSk());
                  return repository.queryIndex("by-status", pk, 10, null);
                })
            .doOnNext(index -> assertEquals(6, index.content().size()));
    StepVerifier.create(flow.subscribeOn(Schedulers.parallel()))
        .expectNextCount(1)
        .verifyComplete();
  }

  @Test
  void nativeTransactionsAndConcurrentUpdatesKeepSdkConditions() {
    String pk = UUID.randomUUID().toString();
    var flow =
        repository
            .transact(
                b ->
                    b.addPutItem(
                        nativeRepository.table(),
                        TransactPutItemEnhancedRequest.builder(Item.class)
                            .item(new Item(pk, "1", pk))
                            .build()))
            .then(repository.findById(pk, "1"))
            .flatMap(
                item -> {
                  assertEquals(1L, item.getVersion());
                  return Flux.range(0, 8)
                      .flatMap(
                          n -> {
                            var contender = new Item(pk, "1", "winner-" + n);
                            contender.setVersion(item.getVersion());
                            return repository
                                .update(contender)
                                .map(ignored -> 1)
                                .onErrorReturn(ConditionalCheckFailedException.class, 0);
                          },
                          8)
                      .reduce(0, Integer::sum);
                });
    StepVerifier.create(flow.subscribeOn(Schedulers.parallel())).expectNext(1).verifyComplete();
  }
}
