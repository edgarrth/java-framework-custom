package pe.axiz.core.lock;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Native asynchronous lease acquisition. A busy resource is an empty result, not a blocking wait.
 */
@FunctionalInterface
public interface AsyncDistributedLock {
  CompletionStage<Optional<AsyncLockHandle>> tryAcquire(String resource, Duration lease);
}
