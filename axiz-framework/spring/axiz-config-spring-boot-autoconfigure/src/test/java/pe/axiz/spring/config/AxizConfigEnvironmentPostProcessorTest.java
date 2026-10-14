package pe.axiz.spring.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

class AxizConfigEnvironmentPostProcessorTest {

  private final AxizConfigEnvironmentPostProcessor epp = new AxizConfigEnvironmentPostProcessor();

  private static MockEnvironment cluster() {
    return new MockEnvironment()
        .withProperty("spring.application.name", "payments-business")
        .withProperty("KUBERNETES_SERVICE_HOST", "10.0.0.1")
        .withProperty("KUBERNETES_SERVICE_PORT", "443");
  }

  @Test
  void insideClusterImportsConventionalConfigMaps() {
    MockEnvironment env =
        cluster().withProperty("spring.config.import", "optional:file:./extra.yaml");
    epp.postProcessEnvironment(env, new SpringApplication());
    assertEquals("optional:file:./extra.yaml,kubernetes:", env.getProperty("spring.config.import"));
    assertEquals("axiz-common", env.getProperty("spring.cloud.kubernetes.config.sources[0].name"));
    assertEquals(
        "payments-business-errors",
        env.getProperty("spring.cloud.kubernetes.config.sources[2].name"));
    assertEquals("true", env.getProperty("spring.cloud.kubernetes.config.fail-fast"));
    assertEquals("false", env.getProperty("spring.cloud.kubernetes.secrets.enabled"));
  }

  @Test
  void explicitValuesWin() {
    MockEnvironment env =
        cluster().withProperty("spring.cloud.kubernetes.config.fail-fast", "false");
    epp.postProcessEnvironment(env, new SpringApplication());
    assertEquals("false", env.getProperty("spring.cloud.kubernetes.config.fail-fast"));
  }

  @Test
  void localProfileAndOutsideClusterSkipKubernetes() {
    MockEnvironment local = cluster();
    local.setActiveProfiles("local");
    epp.postProcessEnvironment(local, new SpringApplication());
    assertNull(local.getProperty("spring.config.import"));
    assertEquals("false", local.getProperty("spring.cloud.kubernetes.enabled"));

    MockEnvironment laptop = new MockEnvironment();
    epp.postProcessEnvironment(laptop, new SpringApplication());
    assertNull(laptop.getProperty("spring.config.import"));
  }

  @Test
  void parameterStoreGoesBeforeConfigMaps() {
    MockEnvironment env =
        cluster()
            .withProperty("axiz.config.parameter-store.enabled", "true")
            .withProperty("axiz.config.environment", "QA");
    epp.postProcessEnvironment(env, new SpringApplication());
    assertEquals(
        "optional:aws-parameterstore:/axiz/qa/payments-business/,kubernetes:",
        env.getProperty("spring.config.import"));
  }

  @Test
  void requiredPropertiesAreVerified() {
    MockEnvironment env = new MockEnvironment().withProperty("a", "1").withProperty("b", " ");
    new RequiredPropertiesVerifier(env, List.of("a")).afterPropertiesSet();
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () -> new RequiredPropertiesVerifier(env, List.of("a", "b", "c")).afterPropertiesSet());
    assertEquals(true, e.getMessage().contains("b, c"));
  }
}
