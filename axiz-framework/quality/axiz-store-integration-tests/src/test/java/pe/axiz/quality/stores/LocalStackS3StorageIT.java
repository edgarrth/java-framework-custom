package pe.axiz.quality.stores;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.observation.ObservationRegistry;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import pe.axiz.adapter.observability.aws.AsyncAwsClients;
import pe.axiz.adapter.storage.s3.S3AsyncObjectStorage;
import pe.axiz.core.storage.ObjectMetadata;
import pe.axiz.spring.storage.ReactiveStorage;
import pe.axiz.spring.test.BlockHoundExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Native S3 streaming and lifecycle on a real HTTP backend, including watched SDK threads. */
@ExtendWith(BlockHoundExtension.class)
class LocalStackS3StorageIT {
  private static final LocalStackContainer LOCALSTACK =
      new LocalStackContainer(DockerImageName.parse(Docker.LOCALSTACK)).withServices("s3");

  private static final String BUCKET = "axiz-native-storage";
  private static AsyncAwsClients resources;
  private static S3AsyncClient client;
  private static S3Presigner signer;
  private static java.net.http.HttpClient downloadClient;
  private static ReactiveStorage storage;
  private static NettyLifecycleProbe lifecycle;
  private static final AtomicInteger REFRESHES = new AtomicInteger();

  @BeforeAll
  static void start() {
    Docker.require();
    LOCALSTACK.start();
    pe.axiz.spring.foundation.ReactiveInfrastructureBootstrap.initialize();
    lifecycle = new NettyLifecycleProbe();
    downloadClient = java.net.http.HttpClient.newHttpClient();
    var credentials =
        StaticCredentialsProvider.create(
            AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey()));
    try (var setup =
        S3Client.builder()
            .region(Region.of(LOCALSTACK.getRegion()))
            .endpointOverride(LOCALSTACK.getEndpoint())
            .forcePathStyle(true)
            .credentialsProvider(credentials)
            .httpClient(UrlConnectionHttpClient.create())
            .build()) {
      setup.createBucket(b -> b.bucket(BUCKET));
    }
    resources =
        new AsyncAwsClients(
            () -> {
              REFRESHES.incrementAndGet();
              java.util.concurrent.locks.LockSupport.parkNanos(
                  java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(2));
              return credentials.resolveCredentials();
            },
            Clock.systemUTC(),
            Duration.ofMillis(25));
    client =
        resources.configured(
            S3AsyncClient.builder().forcePathStyle(true),
            ObservationRegistry.NOOP,
            key ->
                switch (key) {
                  case "axiz.aws.region" -> LOCALSTACK.getRegion();
                  case "axiz.aws.endpoint" -> LOCALSTACK.getEndpoint().toString();
                  case "axiz.aws.async.connection-ttl", "axiz.aws.async.max-idle-time" -> "100ms";
                  default -> null;
                },
            "s3");
    signer =
        S3Presigner.builder()
            .region(Region.of(LOCALSTACK.getRegion()))
            .endpointOverride(LOCALSTACK.getEndpoint())
            .credentialsProvider(resources.credentialsProvider())
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build();
    storage =
        new ReactiveStorage(
            new S3AsyncObjectStorage(client, signer, BUCKET, null, Duration.ofMinutes(15)));
  }

  @AfterAll
  static void stop() {
    try {
      if (downloadClient != null) downloadClient.close();
      if (signer != null) signer.close();
      if (client != null) client.close();
      if (resources != null) resources.close();
    } finally {
      if (lifecycle != null) lifecycle.close();
      LOCALSTACK.stop();
    }
    assertTrue(
        lifecycle == null || lifecycle.errors.isEmpty(),
        () -> "SDK lifecycle errors: " + lifecycle.errors);
    assertTrue(
        BlockHoundExtension.detectedBlockingCalls().isEmpty(),
        () -> "Original blocking sites: " + BlockHoundExtension.detectedBlockingCalls());
  }

  @Test
  void streamsUploadAndDownloadWithChecksumsAndBackpressureOnNonBlockingThreads() throws Exception {
    byte[] chunk = new byte[8192];
    java.util.Arrays.fill(chunk, (byte) 7);
    var expected = java.security.MessageDigest.getInstance("SHA-256");
    for (int i = 0; i < 64; i++) expected.update(chunk);
    String checksum = Base64.getEncoder().encodeToString(expected.digest());
    var metadata =
        new ObjectMetadata(
            "application/octet-stream",
            64L * chunk.length,
            checksum,
            java.util.Map.of("origin", "test"));
    var upload =
        storage.put("stream/body", Flux.range(0, 64).map(i -> ByteBuffer.wrap(chunk)), metadata);
    StepVerifier.create(upload.subscribeOn(Schedulers.parallel())).verifyComplete();
    StepVerifier.create(storage.head("stream/body").subscribeOn(Schedulers.parallel()))
        .assertNext(head -> assertEquals(metadata.contentLength(), head.contentLength()))
        .verifyComplete();
    var actual = java.security.MessageDigest.getInstance("SHA-256");
    StepVerifier.create(
            storage
                .content("stream/body")
                .limitRate(1)
                .doOnNext(buffer -> actual.update(buffer.duplicate()))
                .map(ByteBuffer::remaining)
                .reduce(0, Integer::sum)
                .subscribeOn(Schedulers.parallel()))
        .expectNext((int) metadata.contentLength())
        .verifyComplete();
    assertEquals(checksum, Base64.getEncoder().encodeToString(actual.digest()));
    StepVerifier.create(storage.content("stream/body").subscribeOn(Schedulers.parallel()), 0)
        .thenRequest(1)
        .expectNextCount(1)
        .thenCancel()
        .verify(Duration.ofSeconds(10));
    StepVerifier.create(storage.get("stream/body").subscribeOn(Schedulers.parallel()))
        .assertNext(pe.axiz.core.storage.AsyncStoredObject::close)
        .verifyComplete();
    StepVerifier.create(
            storage
                .presignGet("stream/body", Duration.ofSeconds(60))
                .flatMap(
                    url ->
                        Mono.fromCompletionStage(
                            downloadClient.sendAsync(
                                java.net.http.HttpRequest.newBuilder(
                                        java.net.URI.create(url.toString()))
                                    .GET()
                                    .build(),
                                java.net.http.HttpResponse.BodyHandlers.discarding())))
                .subscribeOn(Schedulers.parallel()))
        .assertNext(response -> assertEquals(200, response.statusCode()))
        .verifyComplete();
    assertTrue(REFRESHES.get() > 1);
    StepVerifier.create(
            storage
                .presignUpload("upload/from-client", "text/plain", Duration.ofMinutes(1))
                .flatMap(
                    instructions -> {
                      var request = java.net.http.HttpRequest.newBuilder(instructions.url());
                      instructions
                          .headers()
                          .forEach(
                              (name, values) ->
                                  values.forEach(value -> request.header(name, value)));
                      return Mono.fromCompletionStage(
                          downloadClient.sendAsync(
                              request
                                  .PUT(
                                      java.net.http.HttpRequest.BodyPublishers.ofByteArray(
                                          new byte[] {1, 2, 3, 4}))
                                  .build(),
                              java.net.http.HttpResponse.BodyHandlers.discarding()));
                    })
                .subscribeOn(Schedulers.parallel()))
        .assertNext(response -> assertEquals(200, response.statusCode()))
        .verifyComplete();
    StepVerifier.create(storage.head("upload/from-client").subscribeOn(Schedulers.parallel()))
        .assertNext(head -> assertEquals(4, head.contentLength()))
        .verifyComplete();
  }

  @Test
  void nativeSdkBodyPreservesAutomaticChecksumsForSmallUploads() {
    StepVerifier.create(
            Flux.range(0, 32)
                .flatMap(
                    index ->
                        Mono.fromCompletionStage(
                            () ->
                                client.putObject(
                                    software.amazon.awssdk.services.s3.model.PutObjectRequest
                                        .builder()
                                        .bucket(BUCKET)
                                        .key("sdk/" + index)
                                        .build(),
                                    software.amazon.awssdk.core.async.AsyncRequestBody.fromBytes(
                                        new byte[] {1}))),
                    8)
                .subscribeOn(Schedulers.parallel()))
        .expectNextCount(32)
        .verifyComplete();
  }

  @Test
  void paginationMissingObjectsAndReconnectionUseNativeRequests() {
    StepVerifier.create(
            Flux.range(0, 4)
                .flatMap(
                    index ->
                        storage.put(
                            "list/" + index,
                            Flux.just(ByteBuffer.wrap(new byte[] {(byte) index.intValue()})),
                            ObjectMetadata.of("t", 1)))
                .then(storage.list("list/", 2))
                .subscribeOn(Schedulers.parallel()))
        .assertNext(keys -> assertEquals(java.util.List.of("list/0", "list/1"), keys))
        .verifyComplete();
    StepVerifier.create(storage.head("absent").subscribeOn(Schedulers.parallel())).verifyComplete();
    StepVerifier.create(storage.content("absent").subscribeOn(Schedulers.parallel()))
        .verifyComplete();
    // Let the configured idle/TTL policy retire sockets, then exercise the native reconnect path.
    java.util.concurrent.locks.LockSupport.parkNanos(
        java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(150));
    StepVerifier.create(
            storage
                .delete("list/0")
                .then(storage.head("list/0"))
                .subscribeOn(Schedulers.parallel()))
        .verifyComplete();
  }
}
