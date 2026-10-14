package pe.axiz.spring.sensitive;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pe.axiz.core.sensitive.MaskingPolicy;
import pe.axiz.core.sensitive.ObjectMasker;
import pe.axiz.core.sensitive.OutputChannel;
import pe.axiz.core.sensitive.SensitiveField;
import pe.axiz.core.sensitive.SensitiveKeys;

/**
 * Masking of sensitive data in the three outputs: API responses (Jackson module), logs and audit
 * ({@link ObjectMasker} shared by the logging and audit starters). Active by default.
 */
@AutoConfiguration(
    beforeName = "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration")
@ConditionalOnBooleanProperty(name = "axiz.sensitive.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AxizSensitiveProperties.class)
public class AxizSensitiveAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  MaskingPolicy axizMaskingPolicy(AxizSensitiveProperties p) {
    return new MaskingPolicy(
        Map.of(
            OutputChannel.RESPONSE,
            p.response(),
            OutputChannel.LOG,
            p.log(),
            OutputChannel.AUDIT,
            p.audit()),
        p.fullMaskLevel());
  }

  @Bean
  @ConditionalOnMissingBean
  SensitiveKeys axizSensitiveKeys(AxizSensitiveProperties p) {
    Map<String, SensitiveField> extra = new HashMap<>();
    p.keys().forEach((name, k) -> extra.put(name, new SensitiveField(name, k.level(), k.mask())));
    return SensitiveKeys.withExtra(extra);
  }

  @Bean
  @ConditionalOnMissingBean
  ObjectMasker axizObjectMasker(MaskingPolicy policy, SensitiveKeys keys) {
    return new ObjectMasker(policy, keys);
  }

  /** Registered as a Jackson module bean; Boot adds it to its JSON mapper. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "tools.jackson.databind.json.JsonMapper")
  static class JacksonConfiguration {

    @Bean
    @ConditionalOnMissingBean
    SensitiveJacksonModule axizSensitiveJacksonModule(MaskingPolicy policy) {
      return new SensitiveJacksonModule(policy);
    }
  }
}
