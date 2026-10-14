package pe.axiz.core.secrets.aws;

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
    this.path = path.endsWith("/") ? path : path + "/";
  }

  @Override
  public Optional<SecretValue> resolve(String name) {
    try {
      String value =
          client
              .getParameter(
                  GetParameterRequest.builder().name(path + name).withDecryption(true).build())
              .parameter()
              .value();
      return value == null || value.isEmpty()
          ? Optional.empty()
          : Optional.of(SecretValue.of(value));
    } catch (ParameterNotFoundException e) {
      return Optional.empty();
    }
  }
}
