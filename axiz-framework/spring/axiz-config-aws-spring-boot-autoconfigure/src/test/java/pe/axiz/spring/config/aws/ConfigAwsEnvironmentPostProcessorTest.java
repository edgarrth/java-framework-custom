package pe.axiz.spring.config.aws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;
import pe.axiz.spring.config.AxizConfigEnvironmentPostProcessor;

class ConfigAwsEnvironmentPostProcessorTest {

  private final AxizConfigAwsEnvironmentPostProcessor epp =
      new AxizConfigAwsEnvironmentPostProcessor();

  private static MockEnvironment cluster() {
    return new MockEnvironment()
        .withProperty("axiz.config.environment", "dev")
        .withProperty("spring.application.name", "payments-business")
        .withProperty("KUBERNETES_SERVICE_HOST", "10.0.0.1")
        .withProperty("KUBERNETES_SERVICE_PORT", "443");
  }

  private static final String MOUNTED =
      "optional:file:/etc/axiz/config/common/application.yaml,"
          + "file:/etc/axiz/config/service/application.yaml,"
          + "file:/etc/axiz/config/errors/application.yaml";

  @Test
  void incompleteWebIdentityFailsBeforeConfigBootstrapWhileCompleteDeclarationsAreAllowed() {
    for (String key :
        List.of(
            "AWS_ROLE_ARN",
            "AWS_WEB_IDENTITY_TOKEN_FILE",
            "aws.roleArn",
            "aws.webIdentityTokenFile")) {
      var env = new MockEnvironment().withProperty(key, "fixture");
      assertThrows(
          IllegalStateException.class,
          () -> epp.postProcessEnvironment(env, new SpringApplication()));
    }
    var complete =
        new MockEnvironment()
            .withProperty("AWS_ROLE_ARN", "role")
            .withProperty("AWS_WEB_IDENTITY_TOKEN_FILE", "token");
    epp.postProcessEnvironment(complete, new SpringApplication());
    var blank =
        new MockEnvironment()
            .withProperty("aws.roleArn", " ")
            .withProperty("aws.webIdentityTokenFile", "token");
    assertThrows(
        IllegalStateException.class,
        () -> epp.postProcessEnvironment(blank, new SpringApplication()));
  }

  @Test
  void parameterStoreGoesBeforeConfigMaps() {
    MockEnvironment env =
        cluster()
            .withProperty("axiz.config.parameter-store.enabled", "true")
            .withProperty("axiz.config.parameter-store.application", "payments-business")
            .withProperty("axiz.config.environment", "QA");
    epp.postProcessEnvironment(env, new SpringApplication());
    new AxizConfigEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());
    assertEquals(
        "optional:aws-parameterstore:/axiz/qa/payments-business/," + MOUNTED,
        env.getProperty("spring.config.import"));
  }

  @Test
  void parameterStoreClientsExistOnlyWhenParameterStoreIsUsed() {
    // sin SsmClient (y sin necesidad de región AWS) salvo que el servicio active Parameter Store
    MockEnvironment laptop = new MockEnvironment();
    epp.postProcessEnvironment(laptop, new SpringApplication());
    assertEquals("false", laptop.getProperty("spring.cloud.aws.parameterstore.enabled"));

    // Un selector tardío debe fallar antes de que se cree un cliente.
    MockEnvironment enabled = cluster();
    epp.postProcessEnvironment(enabled, new SpringApplication());
    enabled.setProperty("axiz.config.parameter-store.enabled", "true");
    assertThrows(
        IllegalStateException.class,
        () ->
            new AxizConfigAwsValidationEnvironmentPostProcessor()
                .postProcessEnvironment(enabled, new SpringApplication()));

    MockEnvironment explicit =
        new MockEnvironment().withProperty("spring.cloud.aws.parameterstore.enabled", "true");
    epp.postProcessEnvironment(explicit, new SpringApplication());
    assertEquals("true", explicit.getProperty("spring.cloud.aws.parameterstore.enabled"));
  }

  @Test
  void theSecretsManagerConfigIntegrationIsOffUnlessTheServiceTurnsItOn() {
    // el SDK en el classpath (axiz.secrets) no debe construir un segundo cliente que necesite
    // región
    MockEnvironment laptop = new MockEnvironment();
    epp.postProcessEnvironment(laptop, new SpringApplication());
    assertEquals("false", laptop.getProperty("spring.cloud.aws.secretsmanager.enabled"));

    MockEnvironment explicit =
        new MockEnvironment().withProperty("spring.cloud.aws.secretsmanager.enabled", "true");
    epp.postProcessEnvironment(explicit, new SpringApplication());
    assertEquals("true", explicit.getProperty("spring.cloud.aws.secretsmanager.enabled"));
  }
}
