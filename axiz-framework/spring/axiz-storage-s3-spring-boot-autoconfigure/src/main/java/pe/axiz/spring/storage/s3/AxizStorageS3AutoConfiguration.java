package pe.axiz.spring.storage.s3;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import pe.axiz.adapter.aws.identity.AwsCredentialSource;
import pe.axiz.adapter.aws.transport.AsyncAwsClients;
import pe.axiz.adapter.aws.transport.AwsClientSettings;
import pe.axiz.adapter.observability.aws.AwsObservability;
import pe.axiz.adapter.storage.s3.S3AsyncObjectStorage;
import pe.axiz.adapter.storage.s3.S3ObjectStorage;
import pe.axiz.core.storage.AsyncObjectStorage;
import pe.axiz.core.storage.ObjectStorage;
import pe.axiz.spring.aws.AwsAsyncResources;
import pe.axiz.spring.aws.AwsCapability;
import pe.axiz.spring.aws.AwsIdentity;
import pe.axiz.spring.aws.AwsService;
import pe.axiz.spring.aws.AxizAwsAsyncTransportProperties;
import pe.axiz.spring.aws.AxizAwsCompletionProperties;
import pe.axiz.spring.foundation.ConditionalOnImperativeApplication;
import pe.axiz.spring.storage.AxizStorageProperties;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Almacenamiento en Amazon S3: subida y descarga en streaming, SSE-KMS y URLs prefirmadas con
 * validez acotada.
 */
@AutoConfiguration(
    beforeName = {
      "io.awspring.cloud.autoconfigure.s3.S3AutoConfiguration",
      "pe.axiz.spring.storage.AxizStorageAutoConfiguration"
    })
@ConditionalOnClass(
    name = {
      "pe.axiz.adapter.storage.s3.S3ObjectStorage",
      "software.amazon.awssdk.services.s3.S3Client"
    })
@ConditionalOnProperty(name = "axiz.storage.type", havingValue = "s3", matchIfMissing = true)
@EnableConfigurationProperties({
  AxizStorageProperties.class,
  AxizStorageS3Properties.class,
  AxizAwsCompletionProperties.class
})
public class AxizStorageS3AutoConfiguration {

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnImperativeApplication
  @ConditionalOnMissingBean(ObjectStorage.class)
  static class ImperativeConfiguration {
    ImperativeConfiguration(AxizStorageS3Properties properties) {
      properties.requireBucket();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean({S3Client.class, ObjectStorage.class})
    @ConditionalOnImperativeApplication
    S3Client axizS3Client(
        Environment env, ObjectProvider<ObservationRegistry> registry, AwsIdentity identity) {
      var settings =
          AwsClientSettings.resolve(
              env::getProperty, "s3", AwsClientSettings.RegionPolicy.SDK_CHAIN);
      return AwsObservability.configured(
          identity
              .configure(S3Client.builder(), AwsService.S3, AwsCapability.STORAGE)
              .forcePathStyle(settings.endpointOverridden()),
          registry.getIfAvailable(() -> ObservationRegistry.NOOP),
          env::getProperty,
          "s3");
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean({S3Presigner.class, ObjectStorage.class})
    @ConditionalOnImperativeApplication
    AwsCredentialSource axizS3PresignerIdentity(AwsIdentity identity) {
      return identity.source(AwsService.S3, AwsCapability.STORAGE);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean({S3Presigner.class, ObjectStorage.class})
    @ConditionalOnImperativeApplication
    S3Presigner axizS3Presigner(
        Environment env, @Qualifier("axizS3PresignerIdentity") AwsCredentialSource source) {
      S3Presigner.Builder b = S3Presigner.builder();
      var settings =
          AwsClientSettings.resolve(
              env::getProperty, "s3", AwsClientSettings.RegionPolicy.SDK_CHAIN);
      if (settings.region() != null) b.region(settings.region());
      if (settings.endpoint() != null) {
        b.endpointOverride(settings.endpoint());
        b.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
      }
      try {
        // El prefirmador cierra su proveedor; se usa la misma política de fallo cerrado que el
        // cliente S3.
        return b.credentialsProvider(source.provider()).build();
      } catch (RuntimeException | Error failure) {
        source.close();
        throw failure;
      }
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnImperativeApplication
    ObjectStorage axizS3ObjectStorage(
        S3Client s3,
        S3Presigner presigner,
        AxizStorageProperties p,
        AxizStorageS3Properties s3Properties) {
      String configuredKey = s3Properties.kmsKeyId();
      String kms = configuredKey != null && !configuredKey.isBlank() ? configuredKey : null;
      return new S3ObjectStorage(
          s3, presigner, Objects.requireNonNull(s3Properties.bucket()), kms, p.maxPresignTtl());
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  @ConditionalOnMissingBean(AsyncObjectStorage.class)
  static class ReactiveConfiguration {
    ReactiveConfiguration(AxizStorageS3Properties properties) {
      properties.requireBucket();
    }

    @Bean(destroyMethod = "close")
    @Conditional(DefaultResourcesNeeded.class)
    AsyncAwsClients axizStorageAsyncAwsResources(
        AxizAwsCompletionProperties completion,
        ObjectProvider<MeterRegistry> registries,
        AwsIdentity identity,
        AxizAwsAsyncTransportProperties transport) {
      return AwsAsyncResources.create(
          "axizStorageAsyncAwsResources",
          completion.workers(),
          completion.queueCapacity(),
          registries.getIfAvailable(),
          identity,
          AwsService.S3,
          AwsCapability.STORAGE,
          transport.toSettings());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    S3AsyncClient axizS3AsyncClient(
        @Qualifier("axizStorageAsyncAwsResources") AsyncAwsClients clients,
        Environment env,
        ObjectProvider<ObservationRegistry> observations) {
      var settings =
          AwsClientSettings.resolve(
              env::getProperty, "s3", AwsClientSettings.RegionPolicy.EXPLICIT);
      return AwsObservability.configured(
          clients,
          S3AsyncClient.builder().forcePathStyle(settings.endpointOverridden()),
          observations.getIfAvailable(() -> ObservationRegistry.NOOP),
          env::getProperty,
          "s3");
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    S3Presigner axizReactiveS3Presigner(
        @Qualifier("axizStorageAsyncAwsResources") AsyncAwsClients clients, Environment env) {
      var settings =
          AwsClientSettings.resolve(
              env::getProperty, "s3", AwsClientSettings.RegionPolicy.EXPLICIT);
      var builder =
          S3Presigner.builder()
              .region(Objects.requireNonNull(settings.region()))
              .credentialsProvider(clients.credentialsProvider());
      if (settings.endpoint() != null)
        builder
            .endpointOverride(settings.endpoint())
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
      return builder.build();
    }

    @Bean
    AsyncObjectStorage axizS3AsyncObjectStorage(
        S3AsyncClient s3,
        S3Presigner presigner,
        AxizStorageProperties p,
        AxizStorageS3Properties s3Properties) {
      String key = s3Properties.kmsKeyId();
      return new S3AsyncObjectStorage(
          s3,
          presigner,
          Objects.requireNonNull(s3Properties.bucket()),
          key != null && !key.isBlank() ? key : null,
          p.maxPresignTtl());
    }
  }

  static final class DefaultResourcesNeeded extends AnyNestedCondition {
    DefaultResourcesNeeded() {
      super(ConfigurationPhase.REGISTER_BEAN);
    }

    @ConditionalOnMissingBean(S3AsyncClient.class)
    static class ClientMissing {}

    @ConditionalOnMissingBean({S3Presigner.class, ObjectStorage.class})
    static class PresignerMissing {}
  }
}
