package pe.axiz.spring.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class ConfiguredPropertyConditionTest {
  @Configuration(proxyBeanMethods = false)
  static class Config {
    @Bean
    @ConditionalOnConfiguredProperty("service.destination")
    String destinationSelected() {
      return "selected";
    }
  }

  @Test
  void stringPresenceNeverActsAsABooleanSelectorOrPrematurePlaceholderResolution() {
    var runner = new ApplicationContextRunner().withUserConfiguration(Config.class);
    runner.run(ctx -> assertThat(ctx).doesNotHaveBean("destinationSelected"));
    for (String value : new String[] {"", "false", "${LATE_TEST_RESOURCE}"}) {
      runner
          .withPropertyValues("service.destination=" + value)
          .run(ctx -> assertThat(ctx).hasNotFailed().hasBean("destinationSelected"));
    }
  }
}
