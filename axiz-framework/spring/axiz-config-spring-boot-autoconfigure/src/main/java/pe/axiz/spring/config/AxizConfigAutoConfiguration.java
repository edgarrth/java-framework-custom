package pe.axiz.spring.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/** Startup validation of the externalized configuration. */
@AutoConfiguration
@AutoConfigureOrder(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnBooleanProperty(name = "axiz.config.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AxizConfigProperties.class)
public class AxizConfigAutoConfiguration {

  @Bean
  RequiredPropertiesVerifier axizRequiredPropertiesVerifier(
      Environment environment, AxizConfigProperties properties) {
    return new RequiredPropertiesVerifier(environment, properties.required());
  }
}
