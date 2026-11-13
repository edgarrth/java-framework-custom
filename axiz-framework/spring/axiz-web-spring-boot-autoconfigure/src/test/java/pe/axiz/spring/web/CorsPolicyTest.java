package pe.axiz.spring.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class CorsPolicyTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://*.*",
        "https://*.com",
        "https://*com",
        "https://*.example.com",
        "https://example.com:443",
        "null",
        "https://example.com/path",
        "https://example.com//",
        "https://user@example.com",
        "https://example.com?query",
        "https://example.com#fragment",
        "https://example.com:0",
        "https://example.com:65536",
        "https://example.com:",
        "https://example.com,https://other.com"
      })
  void bothStacksRejectNonExactOrNonCanonicalCredentialedOrigins(String origin) {
    String[] values = {
      "axiz.web.cors.enabled=true",
      "axiz.web.cors.allow-credentials=true",
      "axiz.config.environment=prod",
      "axiz.web.cors.allowed-origins[0]=" + origin
    };
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizWebAutoConfiguration.class))
        .withPropertyValues(values)
        .run(context -> assertThat(context).hasFailed());
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizWebAutoConfiguration.class))
        .withPropertyValues(values)
        .run(context -> assertThat(context).hasFailed());
  }

  @ParameterizedTest
  @ValueSource(strings = {"https://*.*", "https://*.com", "https://*com", "https://*.example.com"})
  void bothStacksRejectPatternsEvenWhenThePatternLooksRestricted(String pattern) {
    String[] values = {
      "axiz.web.cors.enabled=true",
      "axiz.web.cors.allow-credentials=true",
      "axiz.web.cors.allowed-origin-patterns[0]=" + pattern
    };
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizWebAutoConfiguration.class))
        .withPropertyValues(values)
        .run(context -> assertThat(context).hasFailed());
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizWebAutoConfiguration.class))
        .withPropertyValues(values)
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void bothStacksRejectInsecureOrUnrestrictedCredentialedOrigins() {
    String[][] invalid = {
      {"axiz.web.cors.allowed-origins[0]=*", "axiz.web.cors.allow-credentials=true"},
      {"axiz.web.cors.allowed-origin-patterns[0]=*", "axiz.web.cors.allow-credentials=true"},
      {
        "axiz.web.cors.allowed-origin-patterns[0]=https://*", "axiz.web.cors.allow-credentials=true"
      },
      {"axiz.web.cors.allowed-origins[0]=http://app.example.test", "axiz.config.environment=prod"}
    };
    for (String[] values : invalid) {
      new WebApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizWebAutoConfiguration.class))
          .withPropertyValues("axiz.web.cors.enabled=true")
          .withPropertyValues(values)
          .run(context -> assertThat(context).hasFailed());
      new ReactiveWebApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizWebAutoConfiguration.class))
          .withPropertyValues("axiz.web.cors.enabled=true")
          .withPropertyValues(values)
          .run(context -> assertThat(context).hasFailed());
    }
  }

  @Test
  void restrictedHttpsWorksWithoutNeedingAnInsecureException() {
    String[] values = {
      "axiz.web.cors.enabled=true",
      "axiz.config.environment=prod",
      "axiz.web.cors.allowed-origins[0]=https://app.example.test",
      "axiz.web.cors.allow-credentials=true"
    };
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizWebAutoConfiguration.class))
        .withPropertyValues(values)
        .run(context -> assertThat(context).hasNotFailed());
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizWebAutoConfiguration.class))
        .withPropertyValues(values)
        .run(context -> assertThat(context).hasNotFailed());
  }
}
