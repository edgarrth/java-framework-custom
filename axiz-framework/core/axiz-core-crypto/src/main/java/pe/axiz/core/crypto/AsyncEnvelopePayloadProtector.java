package pe.axiz.core.crypto;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import pe.axiz.core.concurrent.Stages;

/**
 * Mismo formato de red del payload que la implementación imperativa, usando solo llamadas nativas
 * al proveedor de claves.
 */
public final class AsyncEnvelopePayloadProtector implements AsyncPayloadProtector {
  private final AsyncEnvelopeEncryptor envelope;

  public AsyncEnvelopePayloadProtector(AsyncEnvelopeEncryptor envelope) {
    this.envelope = Objects.requireNonNull(envelope);
  }

  @Override
  public CompletionStage<byte[]> protect(byte[] plaintext, String context, String binding) {
    return Stages.call(
        () -> {
          PayloadProtection.binding(context, binding);
          return Stages.consume(
              envelope.encrypt(PayloadProtection.plaintext(plaintext), context, binding),
              PayloadProtection::frame,
              bytes -> Arrays.fill(bytes, (byte) 0),
              bytes -> Arrays.fill(bytes, (byte) 0));
        });
  }

  @Override
  public CompletionStage<byte[]> reveal(byte[] stored, String context, String binding) {
    return Stages.call(
        () -> {
          PayloadProtection.binding(context, binding);
          return envelope.decrypt(PayloadProtection.unframe(stored), context, binding);
        });
  }
}
