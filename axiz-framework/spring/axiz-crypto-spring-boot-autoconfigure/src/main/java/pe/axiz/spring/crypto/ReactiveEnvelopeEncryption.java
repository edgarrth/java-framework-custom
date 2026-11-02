package pe.axiz.spring.crypto;

import java.util.Objects;
import pe.axiz.core.crypto.AsyncEnvelopeEncryptor;
import pe.axiz.spring.foundation.ReactiveStages;
import reactor.core.publisher.Mono;

/** Deferred Reactor facade for native KMS envelope encryption and its bounded key caches. */
public final class ReactiveEnvelopeEncryption {
  private final AsyncEnvelopeEncryptor encryptor;

  public ReactiveEnvelopeEncryption(AsyncEnvelopeEncryptor encryptor) {
    this.encryptor = Objects.requireNonNull(encryptor);
  }

  public Mono<byte[]> encrypt(byte[] plaintext, String context) {
    return ReactiveStages.from(() -> encryptor.encrypt(plaintext, context));
  }

  public Mono<byte[]> decrypt(byte[] envelope, String context) {
    return ReactiveStages.from(() -> encryptor.decrypt(envelope, context));
  }
}
