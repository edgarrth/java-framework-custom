package pe.axiz.spring.storage.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
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
import pe.axiz.core.storage.InMemoryAsyncObjectStorage;
import pe.axiz.core.storage.ObjectStorage;
import pe.axiz.spring.aws.AxizAwsIdentityAutoConfiguration;
import pe.axiz.spring.storage.AxizStorageAutoConfiguration;
import pe.axiz.spring.storage.ReactiveStorage;
import reactor.test.StepVerifier;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class ReactiveS3StorageTest {
  private static ReactiveWebApplicationContextRunner reactive() {
    return new ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
        .withConfiguration(
            AutoConfigurations.of(
                AxizStorageS3AutoConfiguration.class, AxizStorageAutoConfiguration.class));
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
}
