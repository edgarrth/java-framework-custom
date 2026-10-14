package pe.axiz.core.secrets;

import java.util.Optional;

/**
 * Resolves secrets by name. Names are logical ({@code payments/db-password}); each resolver maps
 * them to its source. Values are never logged.
 */
@FunctionalInterface
public interface SecretResolver {

  Optional<SecretValue> resolve(String name);

  /** Resolves or fails with {@link SecretNotFoundException}. */
  default SecretValue require(String name) {
    return resolve(name).orElseThrow(() -> new SecretNotFoundException(name));
  }
}
