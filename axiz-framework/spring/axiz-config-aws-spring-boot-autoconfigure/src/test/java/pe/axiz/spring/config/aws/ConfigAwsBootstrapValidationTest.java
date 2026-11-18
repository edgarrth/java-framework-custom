package pe.axiz.spring.config.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

class ConfigAwsBootstrapValidationTest {
  @TempDir Path directory;

  @Configuration(proxyBeanMethods = false)
  static class EmptyApplication {}

  @Test
  void enablingRemoteImportsOnlyAfterConfigDataFailsWithoutRemoteIo() throws Exception {
    Path yaml = directory.resolve("application.yaml");
    Files.writeString(
        yaml, "axiz.config.environment: test\naxiz.config.parameter-store.enabled: true\n");
    assertThatThrownBy(() -> application(Map.of()).run("--spring.config.location=" + yaml.toUri()))
        .hasStackTraceContaining("before Config Data");
  }

  @Test
  void processVariablesSelectTheExpectedRemotePathBeforeLoadingFiles() {
    var env =
        processEnvironment(
            Map.of(
                "AXIZ_CONFIG_ENVIRONMENT", "qa",
                "AXIZ_CONFIG_PARAMETER_STORE_ENABLED", "true",
                "AXIZ_CONFIG_PARAMETER_STORE_APPLICATION", "beneficiaries"));
    new AxizConfigAwsEnvironmentPostProcessor()
        .postProcessEnvironment(env, new SpringApplication());
    assertThat(env.getProperty("spring.config.import"))
        .isEqualTo("optional:aws-parameterstore:/axiz/qa/beneficiaries/");
  }

  @Test
  void missingSelectorFailsBeforeAnyConfigDataImportIsCreated() {
    var env =
        new MockEnvironment()
            .withProperty("axiz.config.environment", "prod")
            .withProperty("axiz.config.parameter-store.enabled", "true");
    assertThatThrownBy(
            () ->
                new AxizConfigAwsEnvironmentPostProcessor()
                    .postProcessEnvironment(env, new SpringApplication()))
        .hasMessageContaining("parameter-store.application");
    assertThat(env.getProperty("spring.config.import")).isNull();
  }

  @Test
  void lateEnvironmentOrDestinationChangesFailWithoutAnotherImport() {
    for (String key :
        new String[] {
          "axiz.config.environment",
          "axiz.config.parameter-store.application",
          "axiz.config.parameter-store.prefix"
        }) {
      var env =
          new MockEnvironment()
              .withProperty("axiz.config.environment", "prod")
              .withProperty("axiz.config.parameter-store.enabled", "true")
              .withProperty("axiz.config.parameter-store.application", "service");
      new AxizConfigAwsEnvironmentPostProcessor()
          .postProcessEnvironment(env, new SpringApplication());
      String original = env.getProperty("spring.config.import");
      env.getPropertySources()
          .addFirst(new MapPropertySource("imported", Map.of(key, "different")));
      assertThatThrownBy(
              () ->
                  new AxizConfigAwsValidationEnvironmentPostProcessor()
                      .postProcessEnvironment(env, new SpringApplication()))
          .isInstanceOf(IllegalStateException.class);
      assertThat(env.getProperty("spring.config.import")).isEqualTo(original);
    }
  }

  @Test
  void remotePrefixIsCanonicalAndCannotEscapeItsConfiguredNamespace() {
    for (String prefix :
        new String[] {
          "axiz", "/", "/axiz/", "/axiz//nested", "/axiz/../other", "/axiz/a b", "/axiz/@"
        }) {
      var env =
          new MockEnvironment()
              .withProperty("axiz.config.environment", "test")
              .withProperty("axiz.config.parameter-store.enabled", "true")
              .withProperty("axiz.config.parameter-store.application", "service")
              .withProperty("axiz.config.parameter-store.prefix", prefix);
      assertThatThrownBy(() -> new ParameterStoreBootstrapSelection(env))
          .isInstanceOf(IllegalStateException.class);
    }
    var env =
        new MockEnvironment()
            .withProperty("axiz.config.environment", "test")
            .withProperty("axiz.config.parameter-store.enabled", "true")
            .withProperty("axiz.config.parameter-store.application", "service")
            .withProperty("axiz.config.parameter-store.prefix", "/A_z-9/second");
    var selection = new ParameterStoreBootstrapSelection(env);
    assertThat(selection.getSource().prefix()).isEqualTo("/A_z-9/second");
    assertThat(selection.getProperty("internal")).isNull();
  }

  @Test
  void resourceIdentityDeduplicatesImportsWithTheSameUnderlyingDestination() {
    var first = new AxizParameterStoreResource("destination", false);
    var same = new AxizParameterStoreResource("destination", true);
    var other = new AxizParameterStoreResource("other", false);
    assertThat(first)
        .isEqualTo(same)
        .isNotEqualTo(other)
        .isNotEqualTo("destination")
        .isNotEqualTo(null);
    assertThat(first.hashCode()).isEqualTo(same.hashCode());
    assertThat(first.toString()).isEqualTo("destination");
  }

  private static SpringApplication application(Map<String, Object> variables) {
    var app = new SpringApplication(EmptyApplication.class);
    app.setWebApplicationType(WebApplicationType.NONE);
    app.setEnvironment(processEnvironment(variables));
    app.setRegisterShutdownHook(false);
    return app;
  }

  private static StandardEnvironment processEnvironment(Map<String, Object> variables) {
    var env = new StandardEnvironment();
    env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    env.getPropertySources()
        .replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new SystemEnvironmentPropertySource("systemEnvironment", variables));
    org.springframework.boot.context.properties.source.ConfigurationPropertySources.attach(env);
    return env;
  }
}
