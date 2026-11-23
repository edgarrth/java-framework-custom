package pe.axiz.core.lock;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Adquisición asíncrona nativa de leases. Un recurso ocupado es un resultado vacío, no una espera
 * bloqueante.
 */
@FunctionalInterface
public interface AsyncDistributedLock {
  CompletionStage<Optional<AsyncLockHandle>> tryAcquire(String resource, Duration lease);
}
