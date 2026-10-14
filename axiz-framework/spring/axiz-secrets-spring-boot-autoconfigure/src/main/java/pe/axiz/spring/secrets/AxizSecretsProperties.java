package pe.axiz.spring.secrets;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Secret resolution ({@code axiz.secrets.*}). AWS Secrets Manager is the source of truth; External
 * Secrets Operator mounts the values as files, which are read first.
 *
 * @param cacheTtl how long resolved values are cached
 * @param directory directory of mounted secret files; also imported as a config tree
 * @param environment whether environment variables are a source (local development)
 * @param secretsManager direct Secrets Manager lookups
 * @param parameterStore direct Parameter Store lookups (SecureString)
 */
@ConfigurationProperties("axiz.secrets")
public record AxizSecretsProperties(
    @DefaultValue("5m") Duration cacheTtl,
    @DefaultValue("/var/run/secrets/axiz") String directory,
    @DefaultValue("true") boolean environment,
    @DefaultValue SecretsManager secretsManager,
    @DefaultValue ParameterStore parameterStore) {

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
