package pe.axiz.spring.featureflags;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.OpenFeatureAPI;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import pe.axiz.core.featureflags.FeatureFlags;

/** Registers the axiz provider with OpenFeature and exposes a {@link Client}. */
@AutoConfiguration(after = AxizFeatureFlagsAutoConfiguration.class)
@ConditionalOnClass(name = "dev.openfeature.sdk.OpenFeatureAPI")
public class AxizOpenFeatureAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  Client axizOpenFeatureClient(FeatureFlags flags) {
    OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    api.setProviderAndWait(new AxizOpenFeatureProvider(flags));
    return api.getClient();
  }
}
