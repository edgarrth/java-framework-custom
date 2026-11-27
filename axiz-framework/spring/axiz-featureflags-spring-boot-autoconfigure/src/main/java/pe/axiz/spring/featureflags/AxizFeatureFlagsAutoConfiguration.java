package pe.axiz.spring.featureflags;

import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import pe.axiz.core.featureflags.FeatureFlags;
import pe.axiz.core.featureflags.FlagDefinition;
import pe.axiz.core.featureflags.FlagProvider;
import pe.axiz.core.featureflags.MapFlagProvider;

/** Feature flags desde la configuración: activación por canal, tenant, atributos o porcentaje. */
@AutoConfiguration
@EnableConfigurationProperties(AxizFeatureFlagsProperties.class)
public class AxizFeatureFlagsAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  FlagProvider axizFlagProvider(AxizFeatureFlagsProperties p) {
    List<FlagDefinition> defs =
        p.flags().entrySet().stream().map(e -> e.getValue().toDefinition(e.getKey())).toList();
    return new MapFlagProvider(defs);
  }

  @Bean
  @ConditionalOnMissingBean
  FeatureFlags axizFeatureFlags(FlagProvider provider) {
    return new FeatureFlags(provider);
  }
}
