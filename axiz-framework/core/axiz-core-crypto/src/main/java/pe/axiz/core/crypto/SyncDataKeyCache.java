package pe.axiz.core.crypto;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import pe.axiz.core.crypto.DataKeyProvider.DataKey;

/** Synchronous acquisition over the same bounded ownership and coalescing policy as native I/O. */
final class SyncDataKeyCache implements AutoCloseable {
  private final AsyncDataKeyCache cache;

  SyncDataKeyCache(Duration maxAge, long maxUses, Clock clock) {
    cache = new AsyncDataKeyCache(maxAge, maxUses, clock);
  }

  DataKey get(String id, Supplier<DataKey> loader) {
    var result =
        cache.get(id, () -> CompletableFuture.completedFuture(loader.get())).toCompletableFuture();
    try {
      return result.get();
    } catch (InterruptedException interrupted) {
      result.cancel(true);
      Thread.currentThread().interrupt();
      throw new CryptoException("Interrupted while awaiting a data key", interrupted);
    } catch (ExecutionException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof RuntimeException runtime) throw runtime;
      if (cause instanceof Error error) throw error;
      throw new CryptoException("Data key unavailable", failure);
    }
  }

  @Override
  public void close() {
    cache.close();
  }
}
