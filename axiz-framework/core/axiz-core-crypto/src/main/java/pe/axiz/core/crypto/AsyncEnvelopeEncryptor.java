package pe.axiz.core.crypto;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import pe.axiz.core.commons.Stages;
import pe.axiz.core.crypto.DataKeyProvider.DataKey;

/**
 * Native asynchronous envelope encryption with bounded caches and coalesced key loads. Construct at
 * startup to initialize the JDK random/cipher providers. No executor or blocking remote calls.
 */
public final class AsyncEnvelopeEncryptor {
  private final AsyncDataKeyProvider provider;
  private final AsyncDataKeyCache encryptKeys;
  private final AsyncDataKeyCache decryptKeys;

  public AsyncEnvelopeEncryptor(
      AsyncDataKeyProvider provider, Duration maxAge, long maxUses, Clock clock) {
    this.provider = Objects.requireNonNull(provider);
    Objects.requireNonNull(clock);
    EnvelopePayload.validatePolicy(Objects.requireNonNull(maxAge), maxUses);
    encryptKeys = new AsyncDataKeyCache(maxAge, maxUses, clock);
    decryptKeys = new AsyncDataKeyCache(maxAge, Long.MAX_VALUE, clock);
    // Seed this random instance and discover cipher providers before any application subscription.
    AesGcm.encrypt(AesGcm.newKey(), new byte[0], new byte[0]);
  }

  public CompletionStage<byte[]> encrypt(byte[] plaintext, String context) {
    return Stages.call(
        () -> {
          byte[] snapshot = plaintext.clone();
          return Stages.map(
              encryptKeys.get(context, () -> provider.generate(context)),
              key -> EnvelopePayload.seal(snapshot, key.plaintext(), key.encrypted(), context));
        });
  }

  public CompletionStage<byte[]> decrypt(byte[] envelope, String context) {
    return Stages.call(
        () -> {
          EnvelopePayload payload = EnvelopePayload.parse(envelope);
          byte[] encryptedKey = payload.encryptedKey();
          String id = EnvelopeEncryptor.cacheId(encryptedKey, context);
          return Stages.map(
              decryptKeys.get(
                  id,
                  () ->
                      Stages.map(
                          provider.decrypt(encryptedKey, context),
                          plaintext -> new DataKey(plaintext, encryptedKey))),
              key -> payload.open(key.plaintext(), context));
        });
  }
}
