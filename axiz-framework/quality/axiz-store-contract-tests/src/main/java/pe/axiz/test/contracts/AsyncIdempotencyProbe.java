package pe.axiz.test.contracts;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.idempotency.AsyncIdempotencyStore;
import pe.axiz.core.idempotency.IdempotencyRecord;
import pe.axiz.core.idempotency.IdempotencyStore;
import pe.axiz.core.idempotency.StoredResponse;

/**
 * Solo frontera de pruebas: ejecuta el mismo contrato de propiedad y expiración contra un almacén
 * asíncrono nativo con esperas acotadas.
 */
public final class AsyncIdempotencyProbe implements IdempotencyStore {
  private final AsyncIdempotencyStore store;

  public AsyncIdempotencyProbe(AsyncIdempotencyStore store) {
    this.store = store;
  }

  private static <T> T await(CompletionStage<T> result) {
    try {
      return result.toCompletableFuture().get(10, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted async contract", interrupted);
    } catch (ExecutionException | TimeoutException failure) {
      throw new AssertionError("Asynchronous store contract failed", failure);
    }
  }

  @Override
  public boolean compareAndSet(
      IdempotencyRecord expected, @Nullable IdempotencyRecord replacement) {
    return await(store.compareAndSet(expected, replacement));
  }

  @Override
  public boolean tryStart(IdempotencyRecord record) {
    return await(store.tryStart(record));
  }

  @Override
  public Optional<IdempotencyRecord> find(String key, Instant now) {
    return await(store.find(key, now));
  }

  @Override
  public boolean complete(String key, String owner, StoredResponse response, Instant expiresAt) {
    return await(store.complete(key, owner, response, expiresAt));
  }

  @Override
  public boolean release(String key, String owner) {
    return await(store.release(key, owner));
  }
}
