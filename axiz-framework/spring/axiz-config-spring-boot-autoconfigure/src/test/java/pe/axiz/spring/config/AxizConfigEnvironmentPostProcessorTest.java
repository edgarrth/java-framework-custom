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

  private static final String MOUNTED =
      "optional:file:/etc/axiz/config/common/application.yaml,"
          + "file:/etc/axiz/config/service/application.yaml,"
          + "file:/etc/axiz/config/errors/application.yaml";

  @Test
  void insideClusterImportsTheMountedConfigMaps() {
    // shared (optional), then the service and its error catalog (required): later files win
    MockEnvironment env =
        cluster().withProperty("spring.config.import", "optional:file:./extra.yaml");
    epp.postProcessEnvironment(env, new SpringApplication());
    assertEquals("optional:file:./extra.yaml," + MOUNTED, env.getProperty("spring.config.import"));
  }

  @Test
  void theMountDirectoryCanBeChangedAndEntriesAreNotRepeated() {
    MockEnvironment env =
        cluster()
            .withProperty("axiz.config.directory", "/config/")
            .withProperty("spring.config.import", "file:/config/service/application.yaml");
    epp.postProcessEnvironment(env, new SpringApplication());
    assertEquals(
        "file:/config/service/application.yaml,optional:file:/config/common/application.yaml,"
            + "file:/config/errors/application.yaml",
        env.getProperty("spring.config.import"));
  }

  @Test
  void theKubernetesPlatformCanBeDeclared() {
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("spring.application.name", "payments-business")
            .withProperty("spring.main.cloud-platform", "kubernetes");
    epp.postProcessEnvironment(env, new SpringApplication());
    assertEquals(MOUNTED, env.getProperty("spring.config.import"));

    MockEnvironment none = cluster().withProperty("spring.main.cloud-platform", "none");
    epp.postProcessEnvironment(none, new SpringApplication());
    assertNull(none.getProperty("spring.config.import"));
  }

  @Test
  void disabledConventionsChangeNothing() {
    MockEnvironment env = cluster().withProperty("axiz.config.enabled", "false");
    epp.postProcessEnvironment(env, new SpringApplication());
    assertNull(env.getProperty("spring.config.import"));
    assertNull(env.getProperty("spring.cloud.aws.secretsmanager.enabled"));
  }

  @Test
  void localProfileAndOutsideClusterSkipTheConfigMaps() {
    MockEnvironment local = cluster();
    local.setActiveProfiles("local");
    epp.postProcessEnvironment(local, new SpringApplication());
    assertNull(local.getProperty("spring.config.import"));

    MockEnvironment test = cluster().withProperty("spring.profiles.active", "dev, test");
    epp.postProcessEnvironment(test, new SpringApplication());
    assertNull(test.getProperty("spring.config.import"));

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
        "optional:aws-parameterstore:/axiz/qa/payments-business/," + MOUNTED,
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

  @Test
  void parameterStoreClientsExistOnlyWhenParameterStoreIsUsed() {
    // no SsmClient (and no AWS region needed) unless the service turns Parameter Store on
    MockEnvironment laptop = new MockEnvironment();
    epp.postProcessEnvironment(laptop, new SpringApplication());
    assertEquals("false", laptop.getProperty("spring.cloud.aws.parameterstore.enabled"));

    // the switch may come from the service's own configuration, read when it is bound
    MockEnvironment enabled = cluster();
    epp.postProcessEnvironment(enabled, new SpringApplication());
    enabled.setProperty("axiz.config.parameter-store.enabled", "true");
    assertEquals("true", enabled.getProperty("spring.cloud.aws.parameterstore.enabled"));

    MockEnvironment explicit =
        new MockEnvironment().withProperty("spring.cloud.aws.parameterstore.enabled", "true");
    epp.postProcessEnvironment(explicit, new SpringApplication());
    assertEquals("true", explicit.getProperty("spring.cloud.aws.parameterstore.enabled"));
  }

  @Test
  void theSecretsManagerConfigIntegrationIsOffUnlessTheServiceTurnsItOn() {
    // the SDK on the classpath (axiz.secrets) must not build a second client that needs a region
    MockEnvironment laptop = new MockEnvironment();
    epp.postProcessEnvironment(laptop, new SpringApplication());
    assertEquals("false", laptop.getProperty("spring.cloud.aws.secretsmanager.enabled"));

    MockEnvironment explicit =
        new MockEnvironment().withProperty("spring.cloud.aws.secretsmanager.enabled", "true");
    epp.postProcessEnvironment(explicit, new SpringApplication());
    assertEquals("true", explicit.getProperty("spring.cloud.aws.secretsmanager.enabled"));
  }
}
