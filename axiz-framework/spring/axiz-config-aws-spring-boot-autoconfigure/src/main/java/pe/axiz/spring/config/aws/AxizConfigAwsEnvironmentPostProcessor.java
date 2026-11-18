package pe.axiz.spring.config.aws;

import java.util.List;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import pe.axiz.spring.config.AxizConfigEnvironmentPostProcessor;
import pe.axiz.spring.config.ConfigurationImports;

/** Selects Parameter Store before mounted ConfigMaps and before any remote access. */
public final class AxizConfigAwsEnvironmentPostProcessor
    implements EnvironmentPostProcessor, Ordered {
  @Override
  public int getOrder() {
    return AxizConfigEnvironmentPostProcessor.ORDER - 1;
  }

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
    verifyWebIdentity(env);
    var selection = new ParameterStoreBootstrapSelection(env);
    env.getPropertySources().addLast(selection);
    if (!env.getProperty("axiz.config.enabled", Boolean.class, true)) return;
    var selected = selection.getSource();
    if (selected.enabled())
      ConfigurationImports.append(
          env,
          "axizParameterStoreImports",
          List.of(
              "optional:aws-parameterstore:"
                  + selected.prefix()
                  + "/"
                  + selected.environment()
                  + "/"
                  + selected.application()
                  + "/"));
    env.getPropertySources()
        .addLast(
            new MapPropertySource(
                "axizConfigAwsDefaults",
                Map.of(
                    "spring.cloud.aws.parameterstore.enabled",
                        "${axiz.config.parameter-store.enabled:false}",
                    "spring.cloud.aws.secretsmanager.enabled", "false")));
  }

  private static void verifyWebIdentity(ConfigurableEnvironment env) {
    String role = env.getProperty("aws.roleArn");
    if (role == null) role = env.getProperty("AWS_ROLE_ARN");
    String token = env.getProperty("aws.webIdentityTokenFile");
    if (token == null) token = env.getProperty("AWS_WEB_IDENTITY_TOKEN_FILE");
    if ((role != null || token != null)
        && (role == null || role.isBlank() || token == null || token.isBlank()))
      throw new IllegalStateException(
          "IRSA requires both AWS_ROLE_ARN and AWS_WEB_IDENTITY_TOKEN_FILE");
  }
}
