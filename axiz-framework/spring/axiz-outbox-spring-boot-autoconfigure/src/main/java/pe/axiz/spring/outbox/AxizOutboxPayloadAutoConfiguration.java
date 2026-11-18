package pe.axiz.spring.outbox;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pe.axiz.core.crypto.AsyncEnvelopeEncryptor;
import pe.axiz.core.crypto.AsyncPayloadProtector;
import pe.axiz.core.crypto.EnvelopeEncryptor;
import pe.axiz.core.crypto.PayloadProtectionMode;
import pe.axiz.core.crypto.PayloadProtector;

/**
 * Selects protection independently from store integration; absent keys fail closed in envelope
 * mode.
 */
@AutoConfiguration(
    beforeName = {
      "pe.axiz.spring.outbox.AxizOutboxAutoConfiguration",
      "pe.axiz.spring.outbox.jdbc.AxizOutboxJdbcAutoConfiguration",
      "pe.axiz.spring.outbox.r2dbc.AxizOutboxR2dbcAutoConfiguration"
    },
    afterName = "pe.axiz.spring.crypto.kms.AxizCryptoKmsAutoConfiguration")
@ConditionalOnBooleanProperty(name = "axiz.outbox.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AxizOutboxProtectionProperties.class)
public class AxizOutboxPayloadAutoConfiguration {
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(name = "axiz.outbox.store", havingValue = "jdbc", matchIfMissing = true)
  static class Imperative {
    @Bean
    PayloadProtector axizOutboxPayloadProtector(
        AxizOutboxProtectionProperties p, ObjectProvider<EnvelopeEncryptor> envelope) {
      return p.payloadProtection().create(envelope::getObject);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(name = "axiz.outbox.store", havingValue = "r2dbc")
  static class Native {
    @Bean
    AsyncPayloadProtector axizOutboxAsyncPayloadProtector(
        AxizOutboxProtectionProperties p, ObjectProvider<AsyncEnvelopeEncryptor> envelope) {
      return p.payloadProtection().createAsync(envelope::getObject);
    }

    @Bean
    PayloadProtector axizOutboxPayloadProtector(
        AxizOutboxProtectionProperties p,
        @Qualifier("axizOutboxAsyncPayloadProtector") AsyncPayloadProtector nativeProtection) {
      return p.payloadProtection() == PayloadProtectionMode.NONE
          ? PayloadProtector.NONE
          : new RelayPayloadReader(nativeProtection, p.payloadReadTimeout());
    }
  }
}
