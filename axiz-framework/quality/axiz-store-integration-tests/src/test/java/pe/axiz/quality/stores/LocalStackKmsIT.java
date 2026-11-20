package pe.axiz.quality.stores;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.localstack.LocalStackContainer;
import pe.axiz.adapter.aws.identity.AwsCredentialSource;
import pe.axiz.adapter.aws.transport.AsyncAwsClients;
import pe.axiz.adapter.aws.transport.AsyncAwsTransportSettings;
import pe.axiz.adapter.crypto.kms.KmsAsyncDataKeyProvider;
import pe.axiz.adapter.crypto.kms.KmsDataKeyProvider;
import pe.axiz.adapter.observability.aws.AwsObservability;
import pe.axiz.core.crypto.AsyncEnvelopeEncryptor;
import pe.axiz.core.crypto.CryptoException;
import pe.axiz.core.crypto.EnvelopeEncryptor;
import pe.axiz.core.crypto.KeyringAsyncDataKeyProvider;
import pe.axiz.core.crypto.KeyringDataKeyProvider;
import pe.axiz.quality.containers.TestContainerImages;
import pe.axiz.spring.crypto.ReactiveEnvelopeEncryption;
import pe.axiz.spring.foundation.ReactiveInfrastructureBootstrap;
import pe.axiz.spring.test.BlockHoundExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsAsyncClient;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.DecryptResponse;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;
import software.amazon.awssdk.services.kms.model.KmsException;

/**
 * Real KMS key operations, rotation/cache bounds, cold crypto, refresh and reconnect under
 * BlockHound.
 */
@ExtendWith(BlockHoundExtension.class)
class LocalStackKmsIT {
  private static LocalStackContainer localstack;
  private static final byte[] PLAIN = "account statement".getBytes(StandardCharsets.UTF_8);
  private static final AtomicInteger REFRESHES = new AtomicInteger();
  private static final Set<String> PROVIDER_THREADS = ConcurrentHashMap.newKeySet();
  private static final AtomicInteger GENERATES = new AtomicInteger();
  private static final AtomicInteger DECRYPTS = new AtomicInteger();
  private static KmsClient sync;
  private static KmsAsyncClient async;
  private static AsyncAwsClients resources;
  private static KmsAsyncDataKeyProvider provider;
  private static EnvelopeEncryptor imperative;
  private static NettyLifecycleProbe lifecycle;

  @BeforeAll
  static void start() {
    Docker.require();
    ReactiveInfrastructureBootstrap.initialize();
    localstack = TestContainerImages.localstack().withServices("kms");
    localstack.start();
    var credentials =
        StaticCredentialsProvider.create(
            AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey()));
    sync =
        KmsClient.builder()
            .endpointOverride(localstack.getEndpoint())
            .region(Region.of(localstack.getRegion()))
            .credentialsProvider(credentials)
            .httpClient(UrlConnectionHttpClient.create())
            .build();
    String keyId = sync.createKey().keyMetadata().keyId();
    lifecycle = new NettyLifecycleProbe();
    resources =
        new AsyncAwsClients(
            AwsCredentialSource.borrowed(
                () -> {
                  PROVIDER_THREADS.add(Thread.currentThread().getName());
                  REFRESHES.incrementAndGet();
                  LockSupport.parkNanos(Duration.ofMillis(2).toNanos());
                  return credentials.resolveCredentials();
                }),
            Clock.systemUTC(),
            Duration.ofMillis(25),
            AsyncAwsTransportSettings.defaults());
    async =
        AwsObservability.configured(
            resources,
            KmsAsyncClient.builder(),
            ObservationRegistry.NOOP,
            key ->
                switch (key) {
                  case "axiz.aws.region" -> localstack.getRegion();
                  case "axiz.config.environment" -> "test";
                  case "axiz.transport.allow-plain-http" -> "true";
                  case "axiz.aws.endpoint" -> localstack.getEndpoint().toString();
                  case "axiz.aws.async.connection-ttl", "axiz.aws.async.max-idle-time" -> "100ms";
                  default -> null;
                },
            "kms");
    provider =
        new KmsAsyncDataKeyProvider(
            new KmsAsyncClient() {
              public CompletableFuture<GenerateDataKeyResponse> generateDataKey(
                  GenerateDataKeyRequest request) {
                GENERATES.incrementAndGet();
                return async.generateDataKey(request);
              }

              public CompletableFuture<DecryptResponse> decrypt(DecryptRequest request) {
                DECRYPTS.incrementAndGet();
                return async.decrypt(request);
              }

              public String serviceName() {
                return "kms";
              }

              public void close() {}
            },
            keyId);
    imperative =
        new EnvelopeEncryptor(
            new KmsDataKeyProvider(sync, keyId), Duration.ofMinutes(1), 100, Clock.systemUTC());
  }

  @AfterAll
  static void stop() {
    if (async != null) async.close();
    if (resources != null) resources.close();
    if (sync != null) sync.close();
    if (localstack != null) localstack.stop();
    if (lifecycle != null) {
      lifecycle.close();
      assertTrue(lifecycle.errors.isEmpty(), () -> "Lifecycle errors: " + lifecycle.errors);
    }
    assertTrue(
        BlockHoundExtension.detectedBlockingCalls().isEmpty(),
        () -> "Original blocking calls: " + BlockHoundExtension.detectedBlockingCalls());
  }

  private static ReactiveEnvelopeEncryption facade(Clock clock, long uses) {
    return new ReactiveEnvelopeEncryption(
        new AsyncEnvelopeEncryptor(provider, Duration.ofMinutes(1), uses, clock));
  }

  @Test
  void nativeAndImperativeEnvelopesInteroperateWithColdCrypto() {
    var encryption = facade(Clock.systemUTC(), 100);
    var sealed = new AtomicReference<byte[]>();
    StepVerifier.create(
            encryption
                .encrypt(PLAIN, "statements")
                .doOnNext(sealed::set)
                .flatMap(ciphertext -> encryption.decrypt(ciphertext, "statements"))
                .subscribeOn(Schedulers.parallel()))
        .assertNext(plain -> assertArrayEquals(PLAIN, plain))
        .expectComplete()
        .verify(Duration.ofSeconds(30));
    assertArrayEquals(PLAIN, imperative.decrypt(sealed.get(), "statements"));
    byte[] ciphertext = imperative.encrypt(PLAIN, "imperative-statements");
    StepVerifier.create(
            encryption
                .decrypt(ciphertext, "imperative-statements")
                .subscribeOn(Schedulers.parallel()))
        .assertNext(plain -> assertArrayEquals(PLAIN, plain))
        .expectComplete()
        .verify(Duration.ofSeconds(30));
  }

  @Test
  void concurrentUsesCoalesceAndRotateAtTheAgeBoundaryDuringRefreshAndReconnect() {
    var clock = new MovingClock();
    var encryption = facade(clock, 5);
    String context = "rotation-" + UUID.randomUUID();
    int generated = GENERATES.get();
    int decrypted = DECRYPTS.get();
    var flow =
        Flux.range(0, 40)
            .flatMap(
                i ->
                    encryption
                        .encrypt(PLAIN, context)
                        .flatMap(ciphertext -> encryption.decrypt(ciphertext, context)),
                20)
            .doOnNext(plain -> assertArrayEquals(PLAIN, plain))
            .then(
                Mono.fromRunnable(
                    () -> {
                      assertEquals(8, GENERATES.get() - generated);
                      assertEquals(8, DECRYPTS.get() - decrypted);
                      clock.now = clock.now.plusSeconds(60);
                    }))
            .then(Mono.delay(Duration.ofMillis(150)))
            .then(encryption.encrypt(PLAIN, context));
    StepVerifier.create(flow.subscribeOn(Schedulers.parallel()))
        .expectNextCount(1)
        .expectComplete()
        .verify(Duration.ofSeconds(30));
    assertEquals(9, GENERATES.get() - generated);
    assertTrue(REFRESHES.get() > 1);
    assertTrue(
        PROVIDER_THREADS.stream()
            .noneMatch(name -> name.contains("Netty") || name.startsWith("parallel-")),
        PROVIDER_THREADS.toString());
  }

  @Test
  void invalidContextAndWrappedKeysRetainKmsFailuresAndMalformedHeadersSendNoIo() {
    var encryption = facade(Clock.systemUTC(), 100);
    var flow =
        encryption
            .encrypt(PLAIN, "correct-context")
            .flatMap(ciphertext -> encryption.decrypt(ciphertext, "wrong-context"));
    StepVerifier.create(flow.subscribeOn(Schedulers.parallel()))
        .expectErrorMatches(
            error -> error instanceof CryptoException && error.getCause() instanceof KmsException)
        .verify(Duration.ofSeconds(30));
    StepVerifier.create(
            Mono.fromCompletionStage(() -> provider.decrypt(new byte[] {1, 2, 3}, "account"))
                .subscribeOn(Schedulers.parallel()))
        .expectError(CryptoException.class)
        .verify(Duration.ofSeconds(30));
    int before = DECRYPTS.get();
    StepVerifier.create(
            encryption.decrypt(new byte[] {0, 1, 1}, "account").subscribeOn(Schedulers.parallel()))
        .expectError(CryptoException.class)
        .verify(Duration.ofSeconds(30));
    assertEquals(before, DECRYPTS.get());
  }

  private static final class MovingClock extends Clock {
    volatile Instant now = Instant.now();

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return Clock.fixed(now, zone);
    }

    public Instant instant() {
      return now;
    }
  }

  @Test
  void distinctKmsKeysHaveBoundedExplicitDualReadsAndNativeRouting() {
    String oldArn = sync.createKey().keyMetadata().arn();
    String newArn = sync.createKey().keyMetadata().arn();
    var oldProvider = new KmsDataKeyProvider(sync, oldArn);
    var newProvider = new KmsDataKeyProvider(sync, newArn);
    try (var old =
            new EnvelopeEncryptor(
                new KeyringDataKeyProvider(Map.of("old", oldProvider), "old"),
                Duration.ofMinutes(1),
                100,
                Clock.systemUTC());
        var mixed =
            new EnvelopeEncryptor(
                new KeyringDataKeyProvider(Map.of("old", oldProvider, "new", newProvider), "new"),
                Duration.ofMinutes(1),
                100,
                Clock.systemUTC());
        var nativeMixed =
            new AsyncEnvelopeEncryptor(
                new KeyringAsyncDataKeyProvider(
                    Map.of(
                        "old",
                        new KmsAsyncDataKeyProvider(async, oldArn),
                        "new",
                        new KmsAsyncDataKeyProvider(async, newArn)),
                    "new"),
                Duration.ofMinutes(1),
                100,
                Clock.systemUTC())) {
      byte[] before = old.encrypt(PLAIN, "purpose:token-vault", "synthetic-token-binding");
      byte[] after = mixed.encrypt(PLAIN, "purpose:token-vault", "synthetic-token-binding");
      assertEquals("old", KeyringDataKeyProvider.envelopeKeyId(before));
      assertEquals("new", KeyringDataKeyProvider.envelopeKeyId(after));
      assertArrayEquals(
          PLAIN, mixed.decrypt(before, "purpose:token-vault", "synthetic-token-binding"));
      StepVerifier.create(
              Flux.just(before, after)
                  .publishOn(Schedulers.parallel())
                  .flatMap(
                      bytes ->
                          Mono.fromCompletionStage(
                              () ->
                                  nativeMixed.decrypt(
                                      bytes, "purpose:token-vault", "synthetic-token-binding"))))
          .expectNextMatches(bytes -> Arrays.equals(PLAIN, bytes))
          .expectNextMatches(bytes -> Arrays.equals(PLAIN, bytes))
          .verifyComplete();
      StepVerifier.create(
              Mono.defer(
                      () ->
                          Mono.fromCompletionStage(
                              nativeMixed.encrypt(
                                  PLAIN, "purpose:token-vault", "synthetic-token-binding")))
                  .subscribeOn(Schedulers.parallel()))
          .assertNext(
              bytes -> {
                assertEquals("new", KeyringDataKeyProvider.envelopeKeyId(bytes));
              })
          .verifyComplete();
      try (var retired =
          new EnvelopeEncryptor(
              new KeyringDataKeyProvider(Map.of("new", newProvider), "new"),
              Duration.ofMinutes(1),
              100,
              Clock.systemUTC())) {
        assertThrows(
            CryptoException.class,
            () -> retired.decrypt(before, "purpose:token-vault", "synthetic-token-binding"));
        assertArrayEquals(
            PLAIN, retired.decrypt(after, "purpose:token-vault", "synthetic-token-binding"));
      }
      byte[] swapped = before.clone();
      System.arraycopy("new".getBytes(StandardCharsets.US_ASCII), 0, swapped, 8, 3);
      assertThrows(
          CryptoException.class,
          () -> mixed.decrypt(swapped, "purpose:token-vault", "synthetic-token-binding"));
    }
  }
}
