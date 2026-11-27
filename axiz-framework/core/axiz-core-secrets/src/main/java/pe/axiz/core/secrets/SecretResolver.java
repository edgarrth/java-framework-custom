package pe.axiz.core.secrets;

import java.util.Optional;

/**
 * Resuelve secretos por nombre. Los nombres son lógicos ({@code payments/db-password}); cada
 * resolver los mapea a su fuente. Los valores nunca se registran en log.
 */
@FunctionalInterface
public interface SecretResolver {

  Optional<SecretValue> resolve(String name);

  /** Resuelve o falla con {@link SecretNotFoundException}. */
  default SecretValue require(String name) {
    return resolve(name).orElseThrow(() -> new SecretNotFoundException(name));
  }
}
