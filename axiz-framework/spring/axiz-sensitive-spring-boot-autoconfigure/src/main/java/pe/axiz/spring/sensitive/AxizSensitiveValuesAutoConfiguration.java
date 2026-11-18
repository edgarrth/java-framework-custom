package pe.axiz.spring.sensitive;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Intrinsic value safety is independent of optional response annotation masking. */
@AutoConfiguration(
    beforeName = "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration")
@ConditionalOnClass(name = "tools.jackson.databind.json.JsonMapper")
public class AxizSensitiveValuesAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean
  SensitiveValueJacksonModule axizSensitiveValues() {
    return new SensitiveValueJacksonModule();
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "pe.axiz.core.carddata.Pan")
  static class Cards {
    @Bean
    @ConditionalOnMissingBean
    CardDataJacksonModule axizCardData() {
      return CardDataJacksonModule.masking();
    }
  }
}
