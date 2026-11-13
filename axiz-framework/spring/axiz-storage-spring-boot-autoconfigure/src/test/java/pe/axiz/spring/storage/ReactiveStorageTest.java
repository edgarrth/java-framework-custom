package pe.axiz.spring.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.util.TestPropertyValues;
import pe.axiz.adapter.aws.transport.AsyncAwsClients;
import pe.axiz.adapter.storage.s3.S3AsyncObjectStorage;
import pe.axiz.core.storage.AsyncObjectStorage;
import pe.axiz.core.storage.AsyncStoredObject;
import pe.axiz.core.storage.InMemoryAsyncObjectStorage;
import pe.axiz.core.storage.ObjectMetadata;
import pe.axiz.core.storage.ObjectStorage;
import pe.axiz.spring.foundation.aws.AxizAwsIdentityAutoConfiguration;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class ReactiveStorageTest {
  private static ReactiveWebApplicationContextRunner reactive() {
    return new ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
        .withConfiguration(
            AutoConfigurations.of(
                AxizStorageS3AutoConfiguration.class, AxizStorageAutoConfiguration.class));
  }

  @Test
  void memoryWiringSelectsOnlyTheNativeFacadeAndOperationsAreDeferred() {
    reactive()
        .withPropertyValues("axiz.storage.type=memory", "axiz.config.environment=local")
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(AsyncObjectStorage.class)
                  .hasSingleBean(ReactiveStorage.class)
                  .doesNotHaveBean(ObjectStorage.class)
                  .doesNotHaveBean(S3Client.class)
                  .doesNotHaveBean(S3AsyncClient.class);
              ReactiveStorage storage = context.getBean(ReactiveStorage.class);
              var upload =
                  storage.put(
                      "x", Flux.just(ByteBuffer.wrap(new byte[] {7})), ObjectMetadata.of("t", 1));
              StepVerifier.create(storage.head("x")).verifyComplete();
              StepVerifier.create(upload).verifyComplete();
              StepVerifier.create(storage.head("x"))
                  .assertNext(meta -> assertEquals(1, meta.contentLength()))
                  .verifyComplete();
              StepVerifier.create(storage.list("", 1))
                  .assertNext(keys -> assertEquals(List.of("x"), keys))
                  .verifyComplete();
              StepVerifier.create(storage.content("x"))
                  .assertNext(bytes -> assertEquals(7, bytes.get()))
                  .verifyComplete();
              StepVerifier.create(storage.get("x"))
                  .assertNext(AsyncStoredObject::close)
                  .verifyComplete();
              StepVerifier.create(storage.presignGet("x", Duration.ofSeconds(1)))
                  .expectNextCount(1)
                  .verifyComplete();
              StepVerifier.create(storage.presignUpload("x", "t", Duration.ofSeconds(1)))
                  .expectNextCount(1)
                  .verifyComplete();
              StepVerifier.create(storage.presignUpload("x", "t", Duration.ofSeconds(1)))
                  .assertNext(
                      instructions ->
                          assertEquals(List.of("t"), instructions.headers().get("Content-Type")))
                  .verifyComplete();
              StepVerifier.create(storage.delete("x")).verifyComplete();
              StepVerifier.create(storage.content("x")).verifyComplete();
            });
    reactive()
        .withPropertyValues("axiz.storage.type=memory", "axiz.config.environment=prod")
        .run(context -> assertThat(context).hasFailed());
    reactive().run(context -> assertThat(context).hasFailed());
  }

  @Test
  void aSuppliedNativeClientAndSignerDoNotCreateDefaultCredentialsOrSyncClients() {
    S3AsyncClient client = mock(S3AsyncClient.class);
    reactive()
        .withBean(S3AsyncClient.class, () -> client)
        .withBean(
            S3Presigner.class,
            () ->
                S3Presigner.builder()
                    .region(Region.US_EAST_1)
                    .credentialsProvider(
                        StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("test", "test")))
                    .build())
        .withPropertyValues("axiz.storage.bucket=files", "axiz.storage.kms-key-id= ")
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(AsyncObjectStorage.class)
                  .doesNotHaveBean(ObjectStorage.class)
                  .doesNotHaveBean(S3Client.class)
                  .doesNotHaveBean(AsyncAwsClients.class);
              assertInstanceOf(
                  S3AsyncObjectStorage.class, context.getBean(AsyncObjectStorage.class));
              assertSame(client, context.getBean(S3AsyncClient.class));
              StepVerifier.create(
                      context.getBean(ReactiveStorage.class).presignGet("x", Duration.ofSeconds(1)))
                  .expectNextCount(1)
                  .verifyComplete();
            });
  }

  @Test
  void aSuppliedNativePortBacksOffAllDefaultS3Resources() {
    var port = new InMemoryAsyncObjectStorage();
    reactive()
        .withBean(AsyncObjectStorage.class, () -> port)
        .withPropertyValues("axiz.storage.bucket=files")
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(ReactiveStorage.class)
                    .doesNotHaveBean(S3AsyncClient.class)
                    .doesNotHaveBean(S3Presigner.class));
  }

  @Test
  @ResourceLock(Resources.SYSTEM_PROPERTIES)
  void completionMetricsUseApplicationRegistryWithGlobalExportDisabled() {
    var registry = new SimpleMeterRegistry();
    try {
      TestPropertyValues.of("aws.accessKeyId=test", "aws.secretAccessKey=test")
          .applyToSystemProperties(
              () ->
                  reactive()
                      .withConfiguration(AutoConfigurations.of(MetricsAutoConfiguration.class))
                      .withBean(MeterRegistry.class, () -> registry)
                      .withPropertyValues(
                          "management.metrics.use-global-registry=false",
                          "axiz.aws.region=us-east-1",
                          "axiz.storage.bucket=files")
                      .run(
                          context -> {
                            assertThat(context).hasNotFailed().hasSingleBean(AsyncAwsClients.class);
                            assertThat(Metrics.globalRegistry.getRegistries())
                                .doesNotContain(registry);
                            assertThat(
                                    registry
                                        .find("axiz.transport.pending")
                                        .tag("transport", "axizStorageAsyncAwsResources")
                                        .gauge())
                                .isNotNull();
                          }));
      assertThat(
              registry
                  .find("axiz.transport.pending")
                  .tag("transport", "axizStorageAsyncAwsResources")
                  .gauge())
          .isNull();
    } finally {
      registry.close();
    }
  }

  @Test
  @ResourceLock(Resources.SYSTEM_PROPERTIES)
  void defaultNativeClientAndSignerShareOwnedWarmedResourcesAtStartup() {
    TestPropertyValues.of("aws.accessKeyId=test", "aws.secretAccessKey=test")
        .applyToSystemProperties(
            () -> {
              reactive()
                  .withPropertyValues(
                      "axiz.aws.region=us-east-1",
                      "axiz.storage.bucket=files",
                      "axiz.aws.s3.endpoint=https://localhost:4566",
                      "axiz.storage.kms-key-id=alias/files")
                  .run(
                      context -> {
                        assertThat(context)
                            .hasNotFailed()
                            .hasSingleBean(S3AsyncClient.class)
                            .hasSingleBean(AsyncAwsClients.class)
                            .hasSingleBean(S3Presigner.class)
                            .doesNotHaveBean(S3Client.class)
                            .doesNotHaveBean(ObjectStorage.class);
                        StepVerifier.create(
                                context
                                    .getBean(ReactiveStorage.class)
                                    .presignGet("x", Duration.ofMinutes(1)))
                            .assertNext(
                                url ->
                                    assertTrue(
                                        url.toString()
                                            .startsWith("https://localhost:4566/files/x")))
                            .verifyComplete();
                      });
            });
  }

  @Test
  @ResourceLock(Resources.SYSTEM_PROPERTIES)
  void nativeDefaultsWorkWithoutAnEndpointAndMissingPresignRegionFailsAtStartup() {
    TestPropertyValues.of("aws.accessKeyId=test", "aws.secretAccessKey=test")
        .applyToSystemProperties(
            () -> {
              reactive()
                  .withPropertyValues("axiz.aws.region=us-east-1", "axiz.storage.bucket=files")
                  .run(
                      context ->
                          assertThat(context).hasNotFailed().hasSingleBean(S3AsyncClient.class));
              reactive()
                  .withBean(S3AsyncClient.class, () -> mock(S3AsyncClient.class))
                  .withPropertyValues("axiz.storage.bucket=files", "axiz.aws.region= ")
                  .run(
                      context -> {
                        String region = System.getenv("AWS_REGION");
                        if (region == null || region.isBlank()) assertThat(context).hasFailed();
                        else assertThat(context).hasNotFailed().hasSingleBean(S3Presigner.class);
                      });
            });
  }

  @Test
  void lateHeadersAreDiscardedAndClosedAfterDownstreamCancellation() {
    AsyncObjectStorage port = mock(AsyncObjectStorage.class);
    CompletableFuture<Optional<AsyncStoredObject>> headers =
        new CompletableFuture<>() {
          @Override
          public boolean cancel(boolean interrupt) {
            return false;
          }
        };
    when(port.get("x")).thenReturn(headers);
    AtomicInteger closes = new AtomicInteger();
    var subscription =
        new ReactiveStorage(port).get("x").subscribe(object -> fail("Cancelled subscriber"));
    subscription.dispose();
    headers.complete(
        Optional.of(
            new AsyncStoredObject(
                "x",
                ObjectMetadata.of("t", 0),
                JdkFlowAdapter.publisherToFlowPublisher(Flux.empty()),
                closes::incrementAndGet)));
    assertEquals(1, closes.get());
  }

  @Test
  void completionErrorAndCancellationAllCloseTheDownloadAndPendingHeadersCancelNatively() {
    AsyncObjectStorage port = mock(AsyncObjectStorage.class);
    AtomicInteger closes = new AtomicInteger();
    when(port.get("x"))
        .thenAnswer(
            call ->
                CompletableFuture.completedFuture(
                    Optional.of(
                        new AsyncStoredObject(
                            "x",
                            ObjectMetadata.of("t", 1),
                            JdkFlowAdapter.publisherToFlowPublisher(
                                Flux.just(ByteBuffer.wrap(new byte[] {1}))),
                            closes::incrementAndGet))));
    var storage = new ReactiveStorage(port);
    StepVerifier.create(storage.content("x")).expectNextCount(1).verifyComplete();
    assertEquals(1, closes.get());
    when(port.get("x"))
        .thenAnswer(
            call ->
                CompletableFuture.completedFuture(
                    Optional.of(
                        new AsyncStoredObject(
                            "x",
                            ObjectMetadata.of("t", 1),
                            JdkFlowAdapter.publisherToFlowPublisher(
                                Flux.error(new IllegalStateException("body"))),
                            closes::incrementAndGet))));
    StepVerifier.create(storage.content("x")).verifyError(IllegalStateException.class);
    assertEquals(2, closes.get());
    when(port.get("x"))
        .thenAnswer(
            call ->
                CompletableFuture.completedFuture(
                    Optional.of(
                        new AsyncStoredObject(
                            "x",
                            ObjectMetadata.of("t", 1),
                            JdkFlowAdapter.publisherToFlowPublisher(
                                Flux.concat(Flux.just(ByteBuffer.allocate(1)), Flux.never())),
                            closes::incrementAndGet))));
    StepVerifier.create(storage.content("x")).expectNextCount(1).thenCancel().verify();
    assertEquals(3, closes.get());
    var headers = new CompletableFuture<Optional<AsyncStoredObject>>();
    when(port.get("x")).thenReturn(headers);
    StepVerifier.create(storage.get("x")).thenCancel().verify();
    assertTrue(headers.isCancelled());
  }
}
