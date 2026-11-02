package pe.axiz.core.crypto;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import pe.axiz.core.commons.AsyncLoadingCache;
import pe.axiz.core.commons.Stages;
import pe.axiz.core.crypto.DataKeyProvider.DataKey;

/** Applies envelope-key validation to the shared bounded native loading cache. */
final class AsyncDataKeyCache {
  private final AsyncLoadingCache<DataKey> cache;

  AsyncDataKeyCache(Duration maxAge, long maxUses, Clock clock) {
    cache = new AsyncLoadingCache<>(maxAge, maxUses, EnvelopeEncryptor.MAX_CACHED_KEYS, clock);
  }

  CompletionStage<DataKey> get(String id, Supplier<CompletionStage<DataKey>> loader) {
    return cache.get(
        id,
        () ->
            Stages.map(
                Stages.call(loader),
                key -> {
                  AesGcm.key(key.plaintext());
                  int size = key.encrypted().length;
                  if (size < 1 || size > 0xFFFF)
                    throw new CryptoException("Encrypted data key length is invalid");
                  return key;
                }));
  }
}
