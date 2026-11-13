package pe.axiz.adapter.secrets.aws;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import pe.axiz.core.commons.Stages;
import pe.axiz.core.secrets.AsyncSecretResolver;
import pe.axiz.core.secrets.SecretValue;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerAsyncClient;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;

/**
 * Native Secrets Manager lookups. Only a missing secret becomes absence; outages remain failures.
 */
public final class SecretsManagerAsyncResolver implements AsyncSecretResolver {
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Native SDK clients are likewise borrowed, thread-safe resources. Adapters neither "
              + "expose nor close the supplied clients.")
  private final SecretsManagerAsyncClient client;

  private final String prefix;

  public SecretsManagerAsyncResolver(SecretsManagerAsyncClient client, String prefix) {
    this.client = Objects.requireNonNull(client);
    this.prefix = Objects.requireNonNull(prefix);
  }

  @Override
  public CompletionStage<Optional<SecretValue>> resolve(String name) {
    return Stages.recover(
        Stages.call(
            () -> {
              var lookup = SecretsManagerLookup.of(prefix, name);
              return Stages.map(
                  client.getSecretValue(lookup.request()),
                  response -> lookup.read(response.secretString()));
            }),
        failure -> {
          if (failure instanceof ResourceNotFoundException) return Optional.empty();
          throw new CompletionException(failure);
        });
  }
}
