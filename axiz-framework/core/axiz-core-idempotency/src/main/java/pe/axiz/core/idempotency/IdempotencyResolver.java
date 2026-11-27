package pe.axiz.core.idempotency;

import java.util.Optional;

/**
 * Reconciliación específica de la aplicación para una operación incierta; vacío preserva la
 * incertidumbre.
 */
@FunctionalInterface
public interface IdempotencyResolver {
  Optional<Resolution> resolve(String key, IdempotencyRecord record);
}
