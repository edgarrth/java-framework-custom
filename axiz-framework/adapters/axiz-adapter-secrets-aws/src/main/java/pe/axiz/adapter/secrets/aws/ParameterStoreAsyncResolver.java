package pe.axiz.adapter.secrets.aws;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import pe.axiz.core.commons.Stages;
import pe.axiz.core.secrets.AsyncSecretResolver;
import pe.axiz.core.secrets.SecretValue;
import software.amazon.awssdk.services.ssm.SsmAsyncClient;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;

/** Native SSM SecureString lookups with cancellation of the exact SDK request. */
public final class ParameterStoreAsyncResolver implements AsyncSecretResolver {
  private final SsmAsyncClient client;
  private final String path;

  public ParameterStoreAsyncResolver(SsmAsyncClient client, String path) {
    this.client = Objects.requireNonNull(client);
    this.path = ParameterStoreResolver.normalizedPath(path);
  }

  @Override
  public CompletionStage<Optional<SecretValue>> resolve(String name) {
    return Stages.recover(
        Stages.map(
            Stages.call(() -> client.getParameter(ParameterStoreResolver.request(path, name))),
            response -> ParameterStoreResolver.value(response.parameter().value())),
        failure -> {
          if (failure instanceof ParameterNotFoundException) return Optional.empty();
          throw new CompletionException(failure);
        });
  }
}
