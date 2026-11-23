package pe.axiz.adapter.secrets.aws;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.secrets.SecretResolver;
import pe.axiz.core.secrets.SecretValue;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;

/**
 * Resuelve parámetros (incluidos SecureString) desde AWS Systems Manager Parameter Store bajo una
 * ruta como {@code /axiz/prod/payments/}. Fuente complementaria: las credenciales corresponden a
 * Secrets Manager.
 */
public final class ParameterStoreResolver implements SecretResolver {

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "SsmClient is an injected collaborator whose identity and lifecycle must be shared, "
              + "not copied.")
  private final SsmClient client;

  private final String path;

  public ParameterStoreResolver(SsmClient client, String path) {
    this.client = Objects.requireNonNull(client);
    this.path = normalizedPath(path);
  }

  @Override
  public Optional<SecretValue> resolve(String name) {
    try {
      return value(client.getParameter(request(path, name)).parameter().value());
    } catch (ParameterNotFoundException e) {
      return Optional.empty();
    }
  }

  static String normalizedPath(String path) {
    return path.endsWith("/") ? path : path + "/";
  }

  static GetParameterRequest request(String path, String name) {
    return GetParameterRequest.builder()
        .name(path + name.replace('#', '/'))
        .withDecryption(true)
        .build();
  }

  static Optional<SecretValue> value(@Nullable String value) {
    return value == null || value.isEmpty() ? Optional.empty() : Optional.of(SecretValue.of(value));
  }
}
