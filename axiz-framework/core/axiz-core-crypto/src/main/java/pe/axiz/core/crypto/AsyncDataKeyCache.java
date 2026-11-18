package pe.axiz.core.crypto;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import pe.axiz.core.concurrent.AsyncLoadingCache;
import pe.axiz.core.crypto.DataKeyProvider.DataKey;

/** Applies envelope-key validation to the shared bounded native loading cache. */
final class AsyncDataKeyCache implements AutoCloseable {
  private final AsyncLoadingCache<DataKey> cache;

  AsyncDataKeyCache(Duration maxAge, long maxUses, Clock clock) {
    cache =
        new AsyncLoadingCache<>(
            maxAge,
            maxUses,
            EnvelopeEncryptor.MAX_CACHED_KEYS,
            clock,
            DataKey::validForEnvelope,
            DataKey::snapshot,
            DataKey::close);
  }

  CompletionStage<DataKey> get(String id, Supplier<CompletionStage<DataKey>> loader) {
    return cache.get(id, loader);
  }

  @Override
  public void close() {
    cache.close();
  }
}
