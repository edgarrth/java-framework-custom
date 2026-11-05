package pe.axiz.core.idempotency;

import java.util.Optional;

/** Application-specific reconciliation of an uncertain operation; empty preserves uncertainty. */
@FunctionalInterface
public interface IdempotencyResolver {
  Optional<Resolution> resolve(String key, IdempotencyRecord record);
}
