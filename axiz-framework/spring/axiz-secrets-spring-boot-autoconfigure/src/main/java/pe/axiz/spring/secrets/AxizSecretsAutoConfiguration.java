package pe.axiz.spring.secrets;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import pe.axiz.core.secrets.CachingSecretResolver;
import pe.axiz.core.secrets.CompositeSecretResolver;
import pe.axiz.core.secrets.EnvironmentSecretResolver;
import pe.axiz.core.secrets.FileSecretResolver;
import pe.axiz.core.secrets.SecretResolver;

/**
 * {@link SecretResolver} over mounted files, environment variables and the AWS adapters, with a TTL
 * cache. Values are never logged; AWS credentials come from the SDK default chain.
 */
@AutoConfiguration(afterName = "pe.axiz.spring.secrets.AxizSecretsAwsAutoConfiguration")
@ConditionalOnBooleanProperty(name = "axiz.secrets.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AxizSecretsProperties.class)
public class AxizSecretsAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  SecretResolver axizSecretResolver(
      AxizSecretsProperties p, ObjectProvider<SecretSource> sources, ObjectProvider<Clock> clock) {
    List<SecretResolver> chain = new ArrayList<>();
    Path dir = Path.of(p.directory());
    if (Files.isDirectory(dir)) {
      chain.add(new FileSecretResolver(dir));
    }
    if (p.environment()) {
      chain.add(EnvironmentSecretResolver.system());
    }
    sources.orderedStream().map(SecretSource::resolver).forEach(chain::add);
    return new CachingSecretResolver(
        new CompositeSecretResolver(chain), p.cacheTtl(), clock.getIfAvailable(Clock::systemUTC));
  }
}
