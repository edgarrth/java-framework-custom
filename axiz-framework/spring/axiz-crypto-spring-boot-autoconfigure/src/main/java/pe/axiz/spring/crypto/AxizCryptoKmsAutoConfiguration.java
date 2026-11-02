package pe.axiz.spring.crypto;

import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import pe.axiz.adapter.crypto.kms.KmsAsyncDataKeyProvider;
import pe.axiz.adapter.crypto.kms.KmsDataKeyProvider;
import pe.axiz.adapter.observability.aws.AsyncAwsClients;
import pe.axiz.adapter.observability.aws.AwsClients;
import pe.axiz.core.crypto.AsyncDataKeyProvider;
import pe.axiz.core.crypto.AsyncEnvelopeEncryptor;
import pe.axiz.core.crypto.DataKeyProvider;
import pe.axiz.core.crypto.EnvelopeEncryptor;
import software.amazon.awssdk.services.kms.KmsAsyncClient;
import software.amazon.awssdk.services.kms.KmsClient;

/** Envelope encryption with AWS KMS data keys (cached by age and use count). */
@AutoConfiguration(afterName = "pe.axiz.spring.foundation.AxizFoundationAutoConfiguration")
@ConditionalOnClass(
    name = {
      "pe.axiz.adapter.crypto.kms.KmsDataKeyProvider",
      "software.amazon.awssdk.services.kms.KmsClient"
    })
@ConditionalOnProperty("axiz.crypto.kms.key-id")
@EnableConfigurationProperties(AxizCryptoProperties.class)
public class AxizCryptoKmsAutoConfiguration {

  /**
   * Region and endpoint from {@code axiz.aws.region}, {@code axiz.aws.kms.endpoint} or {@code
   * axiz.aws.endpoint}.
   */
  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean
  @pe.axiz.spring.foundation.ConditionalOnImperativeApplication
  KmsClient axizKmsClient(Environment env, ObjectProvider<ObservationRegistry> registry) {
    return AwsClients.configured(
        KmsClient.builder(),
        registry.getIfAvailable(() -> ObservationRegistry.NOOP),
        env::getProperty,
        "kms");
  }

  @Bean
  @ConditionalOnMissingBean
  @pe.axiz.spring.foundation.ConditionalOnImperativeApplication
  DataKeyProvider axizKmsDataKeyProvider(KmsClient kms, AxizCryptoProperties p) {
    return new KmsDataKeyProvider(kms, Objects.requireNonNull(p.kms().keyId()));
  }

  @Bean
  @ConditionalOnMissingBean
  @pe.axiz.spring.foundation.ConditionalOnImperativeApplication
  EnvelopeEncryptor axizEnvelopeEncryptor(
      DataKeyProvider keys, AxizCryptoProperties p, ObjectProvider<Clock> clock) {
    return new EnvelopeEncryptor(
        keys,
        p.kms().dataKeyMaxAge(),
        p.kms().dataKeyMaxUses(),
        clock.getIfAvailable(Clock::systemUTC));
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "reactor.core.publisher.Mono")
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  @ConditionalOnMissingBean(ReactiveEnvelopeEncryption.class)
  static class ReactiveConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean({AsyncDataKeyProvider.class, AsyncEnvelopeEncryptor.class})
    static class NativeConfiguration {
      @Bean(destroyMethod = "close")
      @ConditionalOnMissingBean(KmsAsyncClient.class)
      AsyncAwsClients axizCryptoAsyncAwsResources() {
        return new AsyncAwsClients();
      }

      @Bean(destroyMethod = "close")
      @ConditionalOnMissingBean
      KmsAsyncClient axizKmsAsyncClient(
          @Qualifier("axizCryptoAsyncAwsResources") AsyncAwsClients clients,
          Environment env,
          ObjectProvider<ObservationRegistry> observations) {
        return clients.configured(
            KmsAsyncClient.builder(),
            observations.getIfAvailable(() -> ObservationRegistry.NOOP),
            env::getProperty,
            "kms");
      }

      @Bean
      AsyncDataKeyProvider axizKmsAsyncDataKeyProvider(
          KmsAsyncClient client, AxizCryptoProperties p) {
        return new KmsAsyncDataKeyProvider(client, Objects.requireNonNull(p.kms().keyId()));
      }
    }

    @Bean
    @ConditionalOnMissingBean
    AsyncEnvelopeEncryptor axizAsyncEnvelopeEncryptor(
        AsyncDataKeyProvider keys, AxizCryptoProperties p, ObjectProvider<Clock> clock) {
      return new AsyncEnvelopeEncryptor(
          keys,
          p.kms().dataKeyMaxAge(),
          p.kms().dataKeyMaxUses(),
          clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    ReactiveEnvelopeEncryption axizReactiveEnvelopeEncryption(AsyncEnvelopeEncryptor encryptor) {
      return new ReactiveEnvelopeEncryption(encryptor);
    }
  }
}
