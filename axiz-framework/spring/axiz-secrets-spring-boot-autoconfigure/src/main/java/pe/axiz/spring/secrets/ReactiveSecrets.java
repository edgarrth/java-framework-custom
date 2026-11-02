package pe.axiz.spring.secrets;

import java.util.Objects;
import pe.axiz.core.secrets.AsyncSecretResolver;
import pe.axiz.core.secrets.SecretValue;
import pe.axiz.spring.foundation.ReactiveStages;
import reactor.core.publisher.Mono;

/** Deferred secret lookup; absent values complete empty and failures retain their cause. */
public final class ReactiveSecrets {
  private final AsyncSecretResolver resolver;

  public ReactiveSecrets(AsyncSecretResolver resolver) {
    this.resolver = Objects.requireNonNull(resolver);
  }

  public Mono<SecretValue> resolve(String name) {
    return ReactiveStages.from(() -> resolver.resolve(name)).flatMap(Mono::justOrEmpty);
  }

  public Mono<SecretValue> require(String name) {
    return ReactiveStages.from(() -> resolver.require(name));
  }
}
