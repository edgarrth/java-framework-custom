package pe.axiz.adapter.secrets.aws;

import java.util.Optional;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.secrets.SecretValue;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

/** Shared logical-name/JSON-field policy for synchronous and native Secrets Manager. */
record SecretsManagerLookup(GetSecretValueRequest request, @Nullable String field) {
  static SecretsManagerLookup of(String prefix, String name) {
    int hash = name.indexOf('#');
    String id = prefix + (hash < 0 ? name : name.substring(0, hash));
    return new SecretsManagerLookup(
        GetSecretValueRequest.builder().secretId(id).build(),
        hash < 0 ? null : name.substring(hash + 1));
  }

  Optional<SecretValue> read(@Nullable String secret) {
    if (secret == null) return Optional.empty();
    return field == null
        ? Optional.of(SecretValue.of(secret))
        : SecretsManagerResolver.JsonField.extract(secret, field).map(SecretValue::of);
  }
}
