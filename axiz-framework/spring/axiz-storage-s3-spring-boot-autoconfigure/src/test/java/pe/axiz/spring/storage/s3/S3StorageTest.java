package pe.axiz.spring.storage.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.adapter.storage.s3.S3ObjectStorage;
import pe.axiz.core.storage.ObjectStorage;
import pe.axiz.spring.aws.AxizAwsIdentityAutoConfiguration;
import pe.axiz.spring.storage.AxizStorageAutoConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class S3StorageTest {

  @Test
  void explicitlyEmptyBucketFailsAtStartup() {
    s3.withPropertyValues("axiz.storage.bucket=")
        .run(
            ctx ->
                assertThat(ctx.getStartupFailure()).hasStackTraceContaining("must not be blank"));
  }

  private final ApplicationContextRunner s3 =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
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
    s3.run(
        ctx -> assertThat(ctx.getStartupFailure()).hasStackTraceContaining("bucket is required"));
  }

  @Test
  void aLocalEndpointUsesPathStyleAndAKmsKeyIsOptional() {
    s3.withPropertyValues(
            "axiz.storage.bucket=files",
            "axiz.storage.kms-key-id=alias/files",
            "axiz.aws.endpoint=https://localhost:4566")
        .run(
            ctx -> {
              S3Client client = ctx.getBean(S3Client.class);
              assertEquals(
                  URI.create("https://localhost:4566"),
                  client.serviceClientConfiguration().endpointOverride().orElseThrow());
              assertThat(ctx.getBean(ObjectStorage.class)).isInstanceOf(S3ObjectStorage.class);
            });
    s3.withPropertyValues(
            "axiz.storage.bucket=files",
            "axiz.storage.kms-key-id= ",
            "axiz.aws.s3.endpoint=https://localhost:9000")
        .run(
            ctx ->
                assertEquals(
                    URI.create("https://localhost:9000"),
                    ctx.getBean(S3Client.class)
                        .serviceClientConfiguration()
                        .endpointOverride()
                        .orElseThrow()));
  }

  @Test
  void thePresignerIsBuiltForAServiceSpecificEndpoint() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
        .withConfiguration(AutoConfigurations.of(AxizStorageS3AutoConfiguration.class))
        .withBean(S3Client.class, () -> S3Client.builder().region(Region.US_EAST_1).build())
        .withPropertyValues(
            "axiz.aws.region=us-east-1",
            "axiz.storage.bucket=files",
            "axiz.aws.endpoint=https://localhost:1",
            "axiz.aws.s3.endpoint=https://localhost:4566")
        .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(S3Presigner.class));
  }
}
