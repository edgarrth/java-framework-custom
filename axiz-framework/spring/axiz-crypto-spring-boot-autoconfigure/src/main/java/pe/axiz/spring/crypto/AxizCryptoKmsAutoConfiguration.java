package pe.axiz.spring.crypto;

import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import pe.axiz.core.crypto.DataKeyProvider;
import pe.axiz.core.crypto.EnvelopeEncryptor;
import pe.axiz.core.crypto.kms.KmsDataKeyProvider;
import pe.axiz.core.observability.awssdk.AwsClients;
import software.amazon.awssdk.services.kms.KmsClient;

/** Envelope encryption with AWS KMS data keys (cached by age and use count). */
@AutoConfiguration
@ConditionalOnClass(
    name = {
      "pe.axiz.core.crypto.kms.KmsDataKeyProvider",
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
  KmsClient axizKmsClient(Environment env, ObjectProvider<ObservationRegistry> registry) {
    return AwsClients.configured(
        KmsClient.builder(),
        registry.getIfAvailable(() -> ObservationRegistry.NOOP),
        env::getProperty,
        "kms");
  }

  @Bean
  @ConditionalOnMissingBean
  DataKeyProvider axizKmsDataKeyProvider(KmsClient kms, AxizCryptoProperties p) {
    return new KmsDataKeyProvider(kms, Objects.requireNonNull(p.kms().keyId()));
  }

  @Bean
  @ConditionalOnMissingBean
  EnvelopeEncryptor axizEnvelopeEncryptor(
      DataKeyProvider keys, AxizCryptoProperties p, ObjectProvider<Clock> clock) {
    return new EnvelopeEncryptor(
        keys,
        p.kms().dataKeyMaxAge(),
        p.kms().dataKeyMaxUses(),
        clock.getIfAvailable(Clock::systemUTC));
  }
}
