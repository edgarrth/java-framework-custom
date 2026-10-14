package pe.axiz.core.secrets;

import java.util.List;
import java.util.Optional;

/** Tries resolvers in order; the first one that has the secret wins. */
public final class CompositeSecretResolver implements SecretResolver {

  private final List<SecretResolver> delegates;

  public CompositeSecretResolver(List<SecretResolver> delegates) {
    this.delegates = List.copyOf(delegates);
  }

  @Override
  public Optional<SecretValue> resolve(String name) {
    for (SecretResolver r : delegates) {
      Optional<SecretValue> v = r.resolve(name);
      if (v.isPresent()) {
        return v;
      }
    }
    return Optional.empty();
  }
}
