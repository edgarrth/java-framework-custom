package pe.axiz.spring.crypto.kms;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.HashMap;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import pe.axiz.adapter.aws.transport.AsyncAwsClients;
import pe.axiz.adapter.crypto.kms.KmsAsyncDataKeyProvider;
import pe.axiz.adapter.crypto.kms.KmsDataKeyProvider;
import pe.axiz.adapter.observability.aws.AwsObservability;
import pe.axiz.core.crypto.AsyncDataKeyProvider;
import pe.axiz.core.crypto.AsyncEnvelopeEncryptor;
import pe.axiz.core.crypto.DataKeyProvider;
import pe.axiz.core.crypto.EnvelopeEncryptor;
import pe.axiz.core.crypto.KeyringAsyncDataKeyProvider;
import pe.axiz.core.crypto.KeyringDataKeyProvider;
import pe.axiz.spring.aws.AwsAsyncResources;
import pe.axiz.spring.aws.AwsCapability;
import pe.axiz.spring.aws.AwsIdentity;
import pe.axiz.spring.aws.AwsService;
import pe.axiz.spring.aws.AxizAwsAsyncTransportProperties;
import pe.axiz.spring.aws.AxizAwsCompletionProperties;
import pe.axiz.spring.crypto.ReactiveEnvelopeEncryption;
import pe.axiz.spring.foundation.ConditionalOnConfiguredProperty;
import pe.axiz.spring.foundation.ConditionalOnImperativeApplication;
import software.amazon.awssdk.services.kms.KmsAsyncClient;
import software.amazon.awssdk.services.kms.KmsClient;

/** Envelope encryption with AWS KMS data keys (cached by age and use count). */
@AutoConfiguration(afterName = "pe.axiz.spring.foundation.AxizFoundationAutoConfiguration")
@ConditionalOnClass(
    name = {
      "pe.axiz.adapter.crypto.kms.KmsDataKeyProvider",
      "software.amazon.awssdk.services.kms.KmsClient"
    })
@ConditionalOnConfiguredProperty("axiz.crypto.kms.key-id")
@EnableConfigurationProperties({AxizCryptoKmsProperties.class, AxizAwsCompletionProperties.class})
public class AxizCryptoKmsAutoConfiguration {

  /**
   * Region and endpoint from {@code axiz.aws.region}, {@code axiz.aws.kms.endpoint} or {@code
   * axiz.aws.endpoint}.
   */
  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean({KmsClient.class, DataKeyProvider.class, EnvelopeEncryptor.class})
  @ConditionalOnImperativeApplication
  KmsClient axizKmsClient(
      AxizCryptoKmsProperties properties,
      Environment env,
      ObjectProvider<ObservationRegistry> registry,
      AwsIdentity identity) {
    Objects.requireNonNull(properties.keyId(), "KMS key id is required before opening a client");
    return AwsObservability.configured(
        identity.configure(KmsClient.builder(), AwsService.KMS, AwsCapability.CRYPTO),
        registry.getIfAvailable(() -> ObservationRegistry.NOOP),
        env::getProperty,
        "kms");
  }

  @Bean
  @ConditionalOnMissingBean({DataKeyProvider.class, EnvelopeEncryptor.class})
  @ConditionalOnImperativeApplication
  DataKeyProvider axizKmsDataKeyProvider(KmsClient kms, AxizCryptoKmsProperties p) {
    if (!p.keyRing().isEmpty()) {
      var providers = new HashMap<String, DataKeyProvider>();
      p.keyRing().forEach((route, arn) -> providers.put(route, new KmsDataKeyProvider(kms, arn)));
      return new KeyringDataKeyProvider(providers, p.writeRoute());
    }
    return new KmsDataKeyProvider(kms, Objects.requireNonNull(p.keyId()));
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean
  @ConditionalOnImperativeApplication
  EnvelopeEncryptor axizEnvelopeEncryptor(
      DataKeyProvider keys, AxizCryptoKmsProperties p, ObjectProvider<Clock> clock) {
    return new EnvelopeEncryptor(
        keys, p.dataKeyMaxAge(), p.dataKeyMaxUses(), clock.getIfAvailable(Clock::systemUTC));
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
      AsyncAwsClients axizCryptoAsyncAwsResources(
          AxizCryptoKmsProperties properties,
          AxizAwsCompletionProperties completion,
          ObjectProvider<MeterRegistry> registries,
          AwsIdentity identity,
          AxizAwsAsyncTransportProperties transport) {
        Objects.requireNonNull(
            properties.keyId(), "KMS key id is required before opening a client");
        return AwsAsyncResources.create(
            "axizCryptoAsyncAwsResources",
            completion.workers(),
            completion.queueCapacity(),
            registries.getIfAvailable(),
            identity,
            AwsService.KMS,
            AwsCapability.CRYPTO,
            transport.toSettings());
      }

      @Bean(destroyMethod = "close")
      @ConditionalOnMissingBean
      KmsAsyncClient axizKmsAsyncClient(
          @Qualifier("axizCryptoAsyncAwsResources") AsyncAwsClients clients,
          Environment env,
          ObjectProvider<ObservationRegistry> observations) {
        return AwsObservability.configured(
            clients,
            KmsAsyncClient.builder(),
            observations.getIfAvailable(() -> ObservationRegistry.NOOP),
            env::getProperty,
            "kms");
      }

      @Bean
      AsyncDataKeyProvider axizKmsAsyncDataKeyProvider(
          KmsAsyncClient client, AxizCryptoKmsProperties p) {
        if (!p.keyRing().isEmpty()) {
          var providers = new HashMap<String, AsyncDataKeyProvider>();
          p.keyRing()
              .forEach(
                  (route, arn) -> providers.put(route, new KmsAsyncDataKeyProvider(client, arn)));
          return new KeyringAsyncDataKeyProvider(providers, p.writeRoute());
        }
        return new KmsAsyncDataKeyProvider(client, Objects.requireNonNull(p.keyId()));
      }
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    AsyncEnvelopeEncryptor axizAsyncEnvelopeEncryptor(
        AsyncDataKeyProvider keys, AxizCryptoKmsProperties p, ObjectProvider<Clock> clock) {
      return new AsyncEnvelopeEncryptor(
          keys, p.dataKeyMaxAge(), p.dataKeyMaxUses(), clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    ReactiveEnvelopeEncryption axizReactiveEnvelopeEncryption(AsyncEnvelopeEncryptor encryptor) {
      return new ReactiveEnvelopeEncryption(encryptor);
    }
  }
}
