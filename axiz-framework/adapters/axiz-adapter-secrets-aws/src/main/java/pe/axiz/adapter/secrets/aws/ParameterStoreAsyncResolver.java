package pe.axiz.adapter.secrets.aws;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import pe.axiz.core.concurrent.Stages;
import pe.axiz.core.secrets.AsyncSecretResolver;
import pe.axiz.core.secrets.SecretValue;
import software.amazon.awssdk.services.ssm.SsmAsyncClient;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;

/** Consultas nativas de SecureString en SSM con cancelación de la petición exacta del SDK. */
public final class ParameterStoreAsyncResolver implements AsyncSecretResolver {
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Native SDK clients are likewise borrowed, thread-safe resources. Adapters neither "
              + "expose nor close the supplied clients.")
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
