package pe.axiz.spring.storage;

import pe.axiz.spring.foundation.AxizAwsCompletionProperties;

import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import pe.axiz.adapter.observability.aws.AsyncAwsClients;
import pe.axiz.adapter.observability.aws.AwsClients;
import pe.axiz.adapter.storage.s3.S3ObjectStorage;
import pe.axiz.core.storage.ObjectStorage;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Amazon S3 storage: streaming upload/download, SSE-KMS, presigned URLs with bounded validity. */
@AutoConfiguration(beforeName = "io.awspring.cloud.autoconfigure.s3.S3AutoConfiguration")
@ConditionalOnClass(
    name = {
      "pe.axiz.adapter.storage.s3.S3ObjectStorage",
      "software.amazon.awssdk.services.s3.S3Client"
    })
@ConditionalOnProperty(name = "axiz.storage.type", havingValue = "s3", matchIfMissing = true)
@EnableConfigurationProperties({AxizStorageProperties.class, AxizAwsCompletionProperties.class})
public class AxizStorageS3AutoConfiguration {

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean
  @pe.axiz.spring.foundation.ConditionalOnImperativeApplication
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
  @pe.axiz.spring.foundation.ConditionalOnImperativeApplication
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
      b.serviceConfiguration(
          software.amazon.awssdk.services.s3.S3Configuration.builder()
              .pathStyleAccessEnabled(true)
              .build());
    }
    return b.build();
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty("axiz.storage.bucket")
  @pe.axiz.spring.foundation.ConditionalOnImperativeApplication
  ObjectStorage axizS3ObjectStorage(S3Client s3, S3Presigner presigner, AxizStorageProperties p) {
    String configuredKey = p.kmsKeyId();
    String kms = configuredKey != null && !configuredKey.isBlank() ? configuredKey : null;
    return new S3ObjectStorage(
        s3, presigner, Objects.requireNonNull(p.bucket()), kms, p.maxPresignTtl());
  }

  @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
  @org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
      type =
          org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type
              .REACTIVE)
  @ConditionalOnMissingBean(pe.axiz.core.storage.AsyncObjectStorage.class)
  @ConditionalOnProperty("axiz.storage.bucket")
  static class ReactiveConfiguration {
    @Bean(destroyMethod = "close")
    @org.springframework.context.annotation.Conditional(DefaultResourcesNeeded.class)
    AsyncAwsClients axizStorageAsyncAwsResources(AxizAwsCompletionProperties completion) {
      return new AsyncAwsClients("axizStorageAsyncAwsResources", completion.workers(), completion.queueCapacity());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    software.amazon.awssdk.services.s3.S3AsyncClient axizS3AsyncClient(
        @Qualifier("axizStorageAsyncAwsResources") AsyncAwsClients clients,
        Environment env,
        ObjectProvider<ObservationRegistry> observations) {
      String endpoint =
          env.getProperty("axiz.aws.s3.endpoint", env.getProperty("axiz.aws.endpoint", ""));
      return clients.configured(
          software.amazon.awssdk.services.s3.S3AsyncClient.builder()
              .forcePathStyle(!endpoint.isBlank()),
          observations.getIfAvailable(() -> ObservationRegistry.NOOP),
          env::getProperty,
          "s3");
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    S3Presigner axizReactiveS3Presigner(
        @Qualifier("axizStorageAsyncAwsResources") AsyncAwsClients clients, Environment env) {
      String region = env.getProperty("axiz.aws.region");
      if (region == null || region.isBlank()) region = System.getenv("AWS_REGION");
      if (region == null || region.isBlank())
        throw new IllegalStateException("Async S3 presigning requires an explicit AWS region");
      var builder =
          S3Presigner.builder()
              .region(Region.of(region))
              .credentialsProvider(clients.credentialsProvider());
      String endpoint =
          env.getProperty("axiz.aws.s3.endpoint", env.getProperty("axiz.aws.endpoint", ""));
      if (!endpoint.isBlank())
        builder
            .endpointOverride(java.net.URI.create(endpoint))
            .serviceConfiguration(
                software.amazon.awssdk.services.s3.S3Configuration.builder()
                    .pathStyleAccessEnabled(true)
                    .build());
      return builder.build();
    }

    @Bean
    pe.axiz.core.storage.AsyncObjectStorage axizS3AsyncObjectStorage(
        software.amazon.awssdk.services.s3.S3AsyncClient s3,
        S3Presigner presigner,
        AxizStorageProperties p) {
      String key = p.kmsKeyId();
      return new pe.axiz.adapter.storage.s3.S3AsyncObjectStorage(
          s3,
          presigner,
          Objects.requireNonNull(p.bucket()),
          key != null && !key.isBlank() ? key : null,
          p.maxPresignTtl());
    }
  }

  static final class DefaultResourcesNeeded
      extends org.springframework.boot.autoconfigure.condition.AnyNestedCondition {
    DefaultResourcesNeeded() {
      super(ConfigurationPhase.REGISTER_BEAN);
    }

    @ConditionalOnMissingBean(software.amazon.awssdk.services.s3.S3AsyncClient.class)
    static class ClientMissing {}

    @ConditionalOnMissingBean(S3Presigner.class)
    static class PresignerMissing {}
  }
}
