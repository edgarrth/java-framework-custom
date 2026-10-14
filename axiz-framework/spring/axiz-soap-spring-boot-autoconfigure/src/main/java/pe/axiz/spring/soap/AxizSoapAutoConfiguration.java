package pe.axiz.spring.soap;

import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import pe.axiz.core.secrets.SecretResolver;
import pe.axiz.core.sensitive.ObjectMasker;

/** SOAP clients with safe XML parsing, WS-Security, fault mapping, masking and observations. */
@AutoConfiguration(
    afterName = {
      "pe.axiz.spring.secrets.AxizSecretsAutoConfiguration",
      "pe.axiz.spring.sensitive.AxizSensitiveAutoConfiguration"
    })
@EnableConfigurationProperties(AxizSoapProperties.class)
public class AxizSoapAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  SoapClients axizSoapClients(
      AxizSoapProperties p,
      ObjectProvider<SecretResolver> secrets,
      ObjectProvider<ObjectMasker> masker,
      ObjectProvider<ObservationRegistry> observations,
      ObjectProvider<Clock> clock) {
    return new SoapClients(
        p,
        secrets.getIfAvailable(),
        masker.getIfAvailable(ObjectMasker::defaults),
        observations.getIfAvailable(() -> ObservationRegistry.NOOP),
        clock.getIfAvailable(Clock::systemUTC));
  }
}
