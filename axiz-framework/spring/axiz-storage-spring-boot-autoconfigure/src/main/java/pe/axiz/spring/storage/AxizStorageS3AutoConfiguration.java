package pe.axiz.spring.storage;

import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import pe.axiz.core.observability.awssdk.AwsClients;
import pe.axiz.core.storage.ObjectStorage;
import pe.axiz.core.storage.s3.S3ObjectStorage;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Amazon S3 storage: streaming upload/download, SSE-KMS, presigned URLs with bounded validity. */
@AutoConfiguration(beforeName = "io.awspring.cloud.autoconfigure.s3.S3AutoConfiguration")
@ConditionalOnClass(
    name = {
      "pe.axiz.core.storage.s3.S3ObjectStorage",
      "software.amazon.awssdk.services.s3.S3Client"
    })
@ConditionalOnProperty(name = "axiz.storage.type", havingValue = "s3", matchIfMissing = true)
@EnableConfigurationProperties(AxizStorageProperties.class)
public class AxizStorageS3AutoConfiguration {

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean
  S3Client axizS3Client(Environment env, ObjectProvider<ObservationRegistry> registry) {
    String endpoint =
        env.getProperty("axiz.aws.s3.endpoint", env.getProperty("axiz.aws.endpoint", ""));
    return AwsClients.build(
        S3Client.builder().forcePathStyle(!endpoint.isBlank()),
        registry.getIfAvailable(() -> ObservationRegistry.NOOP),
        env.getProperty("axiz.aws.region"),
        endpoint);
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean
  S3Presigner axizS3Presigner(Environment env) {
    S3Presigner.Builder b = S3Presigner.builder();
    String region = env.getProperty("axiz.aws.region");
    if (region != null) {
      b.region(Region.of(region));
    }
    String endpoint =
        env.getProperty("axiz.aws.s3.endpoint", env.getProperty("axiz.aws.endpoint", ""));
    if (!endpoint.isBlank()) {
      b.endpointOverride(java.net.URI.create(endpoint));
    }
    return b.build();
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty("axiz.storage.bucket")
  ObjectStorage axizS3ObjectStorage(S3Client s3, S3Presigner presigner, AxizStorageProperties p) {
    String configuredKey = p.kmsKeyId();
    String kms = configuredKey != null && !configuredKey.isBlank() ? configuredKey : null;
    return new S3ObjectStorage(
        s3, presigner, Objects.requireNonNull(p.bucket()), kms, p.maxPresignTtl());
  }
}
