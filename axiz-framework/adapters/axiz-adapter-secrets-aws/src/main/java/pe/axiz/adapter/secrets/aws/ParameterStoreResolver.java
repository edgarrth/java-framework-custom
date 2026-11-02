package pe.axiz.adapter.secrets.aws;

import java.util.Objects;
import java.util.Optional;
import pe.axiz.core.secrets.SecretResolver;
import pe.axiz.core.secrets.SecretValue;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;

/**
 * Resolves parameters (including SecureString) from AWS Systems Manager Parameter Store under a
 * path such as {@code /axiz/prod/payments/}. Complementary source: credentials belong in Secrets
 * Manager.
 */
public final class ParameterStoreResolver implements SecretResolver {

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

  static Optional<SecretValue> value(@org.jspecify.annotations.Nullable String value) {
    return value == null || value.isEmpty() ? Optional.empty() : Optional.of(SecretValue.of(value));
  }
}
