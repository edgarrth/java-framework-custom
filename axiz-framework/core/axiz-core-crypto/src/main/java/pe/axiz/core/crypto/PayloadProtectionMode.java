package pe.axiz.core.crypto;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Explicit technical-store policy. Selecting encryption requires the corresponding native stack.
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
