package pe.axiz.core.secrets.aws;

import java.util.Objects;
import java.util.Optional;
import pe.axiz.core.commons.json.JsonReader;
import pe.axiz.core.secrets.SecretResolver;
import pe.axiz.core.secrets.SecretValue;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;

/**
 * Resolves secrets from AWS Secrets Manager. {@code name#field} extracts one field of a JSON
 * secret. Credentials come from the default SDK chain (IRSA or EKS Pod Identity).
 */
public final class SecretsManagerResolver implements SecretResolver {

  private final SecretsManagerClient client;
  private final String prefix;

  public SecretsManagerResolver(SecretsManagerClient client, String prefix) {
    this.client = Objects.requireNonNull(client);
    this.prefix = Objects.requireNonNull(prefix);
  }

  @Override
  public Optional<SecretValue> resolve(String name) {
    int hash = name.indexOf('#');
    String id = prefix + (hash < 0 ? name : name.substring(0, hash));
    String field = hash < 0 ? null : name.substring(hash + 1);
    try {
      String secret =
          client
              .getSecretValue(GetSecretValueRequest.builder().secretId(id).build())
              .secretString();
      if (secret == null) {
        return Optional.empty();
      }
      if (field == null) {
        return Optional.of(SecretValue.of(secret));
      }
      return JsonField.extract(secret, field).map(SecretValue::of);
    } catch (ResourceNotFoundException e) {
      return Optional.empty();
    }
  }

  /** Extracts a top-level field of a JSON secret. */
  static final class JsonField {
    private JsonField() {}

    static Optional<String> extract(String json, String field) {
      try {
        Object value = JsonReader.parseObject(json).get(field);
        if (value == null) {
          return Optional.empty();
        }
        return Optional.of(
            value instanceof java.math.BigDecimal bd ? bd.toPlainString() : value.toString());
      } catch (JsonReader.JsonException e) {
        return Optional.empty();
      }
    }
  }
}
