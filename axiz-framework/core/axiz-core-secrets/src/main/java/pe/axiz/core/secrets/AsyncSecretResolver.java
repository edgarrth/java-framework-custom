package pe.axiz.core.secrets;

import java.util.Optional;
import java.util.concurrent.CompletionStage;
import pe.axiz.core.commons.Stages;

/** Native asynchronous secret resolution. Only absence may fall through to another source. */
@FunctionalInterface
public interface AsyncSecretResolver {
  CompletionStage<Optional<SecretValue>> resolve(String name);

  default CompletionStage<SecretValue> require(String name) {
    return Stages.map(
        Stages.call(() -> resolve(name)),
        value -> value.orElseThrow(() -> new SecretNotFoundException(name)));
  }
}
