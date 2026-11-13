package pe.axiz.spring.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

class MvcCorsMappingTest {
  @ParameterizedTest
  @CsvSource({
    "https://example.com,https://example.com,prod",
    "https://example.com/,https://example.com,prod",
    "HTTPS://EXAMPLE.COM,https://example.com,prod",
    "https://example.com:8443,https://example.com:8443,prod",
    "http://localhost:8080,http://localhost:8080,local",
    "http://localhost,http://localhost,test"
  })
  void configuredOriginsActuallyMatchAndDoNotAuthorizeOtherSites(
      String configured, String requested, String environment) {
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizWebAutoConfiguration.class))
        .withPropertyValues(
            "axiz.web.cors.enabled=true",
            "axiz.web.cors.path-pattern=/**",
            "axiz.web.cors.allow-credentials=true",
            "axiz.config.environment=" + environment,
            "axiz.web.cors.allowed-origins[0]=" + configured)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var registry = new ExposedRegistry();
              context.getBean(AxizWebMvcConfigurer.class).addCorsMappings(registry);
              CorsConfiguration cors = registry.mappings().get("/**");
              assertThat(cors.getAllowCredentials()).isTrue();
              assertThat(cors.checkOrigin(requested)).isEqualTo(requested);
              assertThat(cors.checkOrigin("https://attacker.com")).isNull();
              assertThat(cors.checkOrigin("null")).isNull();
            });
  }

  @ParameterizedTest
  @CsvSource({"http://localhost:80,local", "http://localhost:8080,prod"})
  void invalidHttpConfigurationFailsAtStartup(String origin, String environment) {
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizWebAutoConfiguration.class))
        .withPropertyValues(
            "axiz.web.cors.enabled=true",
            "axiz.web.cors.allow-credentials=true",
            "axiz.config.environment=" + environment,
            "axiz.web.cors.allowed-origins[0]=" + origin)
        .run(context -> assertThat(context).hasFailed());
  }

  private static final class ExposedRegistry extends CorsRegistry {
    Map<String, CorsConfiguration> mappings() {
      return getCorsConfigurations();
    }
  }
}
