package pe.axiz.spring.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.adapter.storage.s3.S3ObjectStorage;
import pe.axiz.core.storage.ObjectMetadata;
import pe.axiz.core.storage.ObjectStorage;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class StorageTest {

  @Test
  void explicitlyEmptyBucketFailsAtStartup() {
    s3.withPropertyValues("axiz.storage.bucket=")
        .run(
            ctx ->
                assertThat(ctx.getStartupFailure()).hasStackTraceContaining("must not be blank"));
  }

  @Test
  void memoryStorageForTests() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizStorageAutoConfiguration.class))
        .withPropertyValues("axiz.storage.type=memory", "axiz.config.environment=local")
        .run(
            ctx -> {
              ObjectStorage storage = ctx.getBean(ObjectStorage.class);
              storage.put(
                  "reports/a.csv",
                  new ByteArrayInputStream("x,y".getBytes()),
                  ObjectMetadata.of("text/csv", 3));
              assertEquals(3, storage.head("reports/a.csv").orElseThrow().contentLength());
              assertTrue(storage.list("reports/", 10).contains("reports/a.csv"));
            });
  }

  private final ApplicationContextRunner s3 =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  AxizStorageS3AutoConfiguration.class, AxizStorageAutoConfiguration.class))
          .withPropertyValues("axiz.aws.region=us-east-1");

  @Test
  void s3IsTheDefaultAndNeedsABucket() {
    s3.withPropertyValues("axiz.storage.bucket=payments-dev-files")
        .run(
            ctx -> {
              assertThat(ctx.getBean(ObjectStorage.class)).isInstanceOf(S3ObjectStorage.class);
              assertThat(ctx).hasSingleBean(S3Client.class).hasSingleBean(S3Presigner.class);
            });
    s3.run(ctx -> assertThat(ctx).doesNotHaveBean(ObjectStorage.class));
  }

  @Test
  void aLocalEndpointUsesPathStyleAndAKmsKeyIsOptional() {
    s3.withPropertyValues(
            "axiz.storage.bucket=files",
            "axiz.storage.kms-key-id=alias/files",
            "axiz.aws.endpoint=http://localhost:4566")
        .run(
            ctx -> {
              S3Client client = ctx.getBean(S3Client.class);
              assertEquals(
                  URI.create("http://localhost:4566"),
                  client.serviceClientConfiguration().endpointOverride().orElseThrow());
              assertThat(ctx.getBean(ObjectStorage.class)).isInstanceOf(S3ObjectStorage.class);
            });
    s3.withPropertyValues(
            "axiz.storage.bucket=files",
            "axiz.storage.kms-key-id= ",
            "axiz.aws.s3.endpoint=http://localhost:9000")
        .run(
            ctx ->
                assertEquals(
                    URI.create("http://localhost:9000"),
                    ctx.getBean(S3Client.class)
                        .serviceClientConfiguration()
                        .endpointOverride()
                        .orElseThrow()));
  }

  @Test
  void thePresignerIsBuiltForAServiceSpecificEndpoint() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizStorageS3AutoConfiguration.class))
        .withBean(S3Client.class, () -> S3Client.builder().region(Region.US_EAST_1).build())
        .withPropertyValues(
            "axiz.aws.region=us-east-1",
            "axiz.aws.endpoint=http://localhost:1",
            "axiz.aws.s3.endpoint=http://localhost:4566")
        .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(S3Presigner.class));
  }

  @Test
  void memoryStorageIsRefusedWhereSeveralReplicasRun() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizStorageAutoConfiguration.class))
        .withPropertyValues("axiz.storage.type=memory", "axiz.config.environment=prod")
        .run(ctx -> assertThat(ctx).hasFailed());
  }
}
