package pe.axiz.spring.secrets.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.core.secrets.SecretResolver;
import pe.axiz.spring.aws.AxizAwsIdentityAutoConfiguration;
import pe.axiz.spring.secrets.AxizSecretsAutoConfiguration;
import pe.axiz.spring.secrets.SecretSource;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.ssm.SsmClient;

class AwsSecretSourcesTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
          .withConfiguration(
              AutoConfigurations.of(
                  AxizSecretsAwsAutoConfiguration.class, AxizSecretsAutoConfiguration.class))
          .withPropertyValues("axiz.aws.region=us-east-1", "axiz.secrets.environment=false");

  @Test
  void awsSourcesAreOffUnlessEnabled() {
    runner.run(
        ctx ->
            assertThat(ctx)
                .hasSingleBean(SecretResolver.class)
                .doesNotHaveBean(SecretSource.class)
                .doesNotHaveBean(SecretsManagerClient.class)
                .doesNotHaveBean(SsmClient.class));
  }

  @Test
  void enabledAwsSourcesAreChainedAfterTheMounts() {
    runner
        .withPropertyValues(
            "axiz.secrets.secrets-manager.enabled=true",
            "axiz.secrets.secrets-manager.prefix=axiz/prod/payments/",
            "axiz.secrets.parameter-store.enabled=true",
            "axiz.secrets.parameter-store.path=/axiz/prod/payments/")
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              List<String> names =
                  ctx.getBeanProvider(SecretSource.class)
                      .orderedStream()
                      .map(SecretSource::name)
                      .sorted()
                      .toList();
              assertEquals(List.of("parameter-store", "secrets-manager"), names);
            });
  }

  @Test
  void parameterStoreNeedsAPath() {
    runner
        .withPropertyValues("axiz.secrets.parameter-store.enabled=true")
        .run(
            ctx ->
                assertThat(ctx)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("axiz.secrets.parameter-store.path is required"));
  }
}
