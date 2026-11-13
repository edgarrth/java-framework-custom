package pe.axiz.adapter.secrets.aws;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.Optional;
import pe.axiz.core.commons.json.JsonReader;
import pe.axiz.core.commons.json.JsonWriter;
import pe.axiz.core.secrets.SecretResolver;
import pe.axiz.core.secrets.SecretValue;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;

/**
 * Resolves secrets from AWS Secrets Manager. {@code name#field} extracts one field of a JSON
 * secret. Credentials come from the default SDK chain (IRSA or EKS Pod Identity).
 */
public final class SecretsManagerResolver implements SecretResolver {

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Native SDK clients are likewise borrowed, thread-safe resources. Adapters neither "
              + "expose nor close the supplied clients.")
  private final SecretsManagerClient client;

  private final String prefix;

  public SecretsManagerResolver(SecretsManagerClient client, String prefix) {
    this.client = Objects.requireNonNull(client);
    this.prefix = Objects.requireNonNull(prefix);
  }

  @Override
  public Optional<SecretValue> resolve(String name) {
    var lookup = SecretsManagerLookup.of(prefix, name);
    try {
      return lookup.read(client.getSecretValue(lookup.request()).secretString());
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
        return Optional.of(value instanceof String text ? text : JsonWriter.write(value));
      } catch (JsonReader.JsonException e) {
        // Parser diagnostics can contain a character of the value. Never put them in errors.
        throw new IllegalStateException(
            "Secret is not a valid JSON object; field extraction failed");
      }
    }
  }
}
