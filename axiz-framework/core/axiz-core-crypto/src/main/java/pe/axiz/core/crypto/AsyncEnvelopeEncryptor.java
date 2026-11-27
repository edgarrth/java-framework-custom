package pe.axiz.core.crypto;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.concurrent.Stages;
import pe.axiz.core.crypto.DataKeyProvider.DataKey;

/**
 * Cifrado envelope asíncrono nativo con cachés acotadas y cargas de claves coalescidas. Se
 * construye al arrancar para inicializar los proveedores de aleatoriedad y de cifrado del JDK. Sin
 * executor ni llamadas remotas bloqueantes.
 */
public final class AsyncEnvelopeEncryptor implements AutoCloseable {
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
    // Se inicializa la semilla de esta instancia aleatoria y se descubren los proveedores de
    // cifrado antes de cualquier suscripción de la aplicación.
    AesGcm.initialize();
  }

  public CompletionStage<byte[]> encrypt(byte[] plaintext, String context) {
    return encrypt(plaintext, context, null);
  }

  @SuppressWarnings("FutureReturnValueIgnored") // La limpieza observa el resultado original.
  public CompletionStage<byte[]> encrypt(
      byte[] plaintext, String context, @Nullable String binding) {
    return Stages.call(
        () -> {
          EnvelopePayload.aad(context, binding);
          byte[] snapshot = plaintext.clone();
          CompletionStage<byte[]> result =
              Stages.consume(
                  encryptKeys.get(context, () -> provider.generate(context)),
                  key -> {
                    synchronized (snapshot) {
                      try {
                        return EnvelopePayload.seal(
                            snapshot, key.plaintext(), key.encrypted(), context, binding);
                      } finally {
                        Arrays.fill(snapshot, (byte) 0);
                      }
                    }
                  },
                  DataKey::close,
                  bytes -> Arrays.fill(bytes, (byte) 0));
          result.whenComplete(
              (ignored, failure) -> {
                synchronized (snapshot) {
                  Arrays.fill(snapshot, (byte) 0);
                }
              });
          return result;
        });
  }

  public CompletionStage<byte[]> decrypt(byte[] envelope, String context) {
    return decrypt(envelope, context, null);
  }

  public CompletionStage<byte[]> decrypt(
      byte[] envelope, String context, @Nullable String binding) {
    return Stages.call(
        () -> {
          EnvelopePayload.aad(context, binding);
          EnvelopePayload payload = EnvelopePayload.parse(envelope);
          byte[] encryptedKey = payload.encryptedKey();
          String id = EnvelopeEncryptor.cacheId(encryptedKey, context);
          return Stages.consume(
              decryptKeys.get(
                  id,
                  () ->
                      Stages.consume(
                          provider.decrypt(encryptedKey, context),
                          plaintext -> new DataKey(plaintext, encryptedKey),
                          bytes -> Arrays.fill(bytes, (byte) 0),
                          DataKey::close)),
              key -> payload.open(key.plaintext(), context, binding),
              DataKey::close,
              bytes -> Arrays.fill(bytes, (byte) 0));
        });
  }

  @Override
  public void close() {
    encryptKeys.close();
    decryptKeys.close();
  }
}
