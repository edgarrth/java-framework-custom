package pe.axiz.spring.secrets.aws;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Secret resolution ({@code axiz.secrets.*}). AWS Secrets Manager is the source of truth; External
 * Secrets Operator mounts the values as files, which are read first.
 *
 * @param secretsManager direct Secrets Manager lookups
 * @param parameterStore direct Parameter Store lookups (SecureString)
 */
@ConfigurationProperties("axiz.secrets")
public record AxizSecretsAwsProperties(
    @DefaultValue SecretsManager secretsManager, @DefaultValue ParameterStore parameterStore) {

  /**
   * Secrets Manager source.
   *
   * @param enabled whether it is queried
   * @param prefix name prefix, e.g. {@code axiz/prod/payments/}
   */
  public record SecretsManager(
      @DefaultValue("false") boolean enabled, @DefaultValue("") String prefix) {}

  /**
   * Parameter Store source.
   *
   * @param enabled whether it is queried
   * @param path parameter path, e.g. {@code /axiz/prod/payments/}
   */
  public record ParameterStore(@DefaultValue("false") boolean enabled, @Nullable String path) {}
}
