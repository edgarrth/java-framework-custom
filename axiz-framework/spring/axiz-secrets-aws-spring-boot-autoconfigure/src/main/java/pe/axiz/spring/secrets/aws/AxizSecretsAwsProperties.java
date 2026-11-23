package pe.axiz.spring.secrets.aws;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Resolución de secretos ({@code axiz.secrets.*}). AWS Secrets Manager es la fuente de verdad;
 * External Secrets Operator monta los valores como archivos, que se leen primero.
 *
 * @param secretsManager consultas directas a Secrets Manager
 * @param parameterStore consultas directas a Parameter Store (SecureString)
 */
@ConfigurationProperties("axiz.secrets")
public record AxizSecretsAwsProperties(
    @DefaultValue SecretsManager secretsManager, @DefaultValue ParameterStore parameterStore) {

  /**
   * Fuente Secrets Manager.
   *
   * @param enabled indica si se consulta
   * @param prefix prefijo de nombre, p. ej. {@code axiz/prod/payments/}
   */
  public record SecretsManager(
      @DefaultValue("false") boolean enabled, @DefaultValue("") String prefix) {}

  /**
   * Fuente Parameter Store.
   *
   * @param enabled indica si se consulta
   * @param path ruta de parámetros, p. ej. {@code /axiz/prod/payments/}
   */
  public record ParameterStore(@DefaultValue("false") boolean enabled, @Nullable String path) {}
}
