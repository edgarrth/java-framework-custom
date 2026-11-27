package pe.axiz.core.crypto;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Política explícita del almacén técnico. Seleccionar cifrado requiere la pila nativa
 * correspondiente.
 */
public enum PayloadProtectionMode {
  NONE,
  ENVELOPE;

  public PayloadProtector create(Supplier<EnvelopeEncryptor> envelopes) {
    return this == NONE
        ? PayloadProtector.NONE
        : new EnvelopePayloadProtector(Objects.requireNonNull(envelopes.get()));
  }

  public AsyncPayloadProtector createAsync(Supplier<AsyncEnvelopeEncryptor> envelopes) {
    return this == NONE
        ? AsyncPayloadProtector.NONE
        : new AsyncEnvelopePayloadProtector(Objects.requireNonNull(envelopes.get()));
  }
}
