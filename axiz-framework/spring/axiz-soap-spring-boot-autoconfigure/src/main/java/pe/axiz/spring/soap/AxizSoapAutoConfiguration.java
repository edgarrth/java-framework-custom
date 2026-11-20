package pe.axiz.spring.soap;

import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import pe.axiz.core.commons.net.TransportSecurityPolicy;
import pe.axiz.core.secrets.SecretResolver;

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
      ObjectProvider<ObservationRegistry> observations,
      ObjectProvider<Clock> clock,
      Environment env,
      ObjectProvider<SslBundles> ssl) {
    return new SoapClients(
        p,
        secrets.getIfAvailable(),
        observations.getIfAvailable(() -> ObservationRegistry.NOOP),
        clock.getIfAvailable(Clock::systemUTC),
        TransportSecurityPolicy.from(env::getProperty),
        ssl.getIfAvailable());
  }
}
