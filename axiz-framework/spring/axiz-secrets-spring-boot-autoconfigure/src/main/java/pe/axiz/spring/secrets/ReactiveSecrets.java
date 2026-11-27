package pe.axiz.spring.secrets;

import java.util.Objects;
import pe.axiz.core.secrets.AsyncSecretResolver;
import pe.axiz.core.secrets.SecretValue;
import pe.axiz.spring.foundation.ReactiveStages;
import reactor.core.publisher.Mono;

/**
 * Búsqueda diferida de secretos; los valores ausentes completan vacío y los fallos conservan su
 * causa.
 */
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
