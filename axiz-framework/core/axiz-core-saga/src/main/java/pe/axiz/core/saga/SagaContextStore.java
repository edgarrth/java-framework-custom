package pe.axiz.core.saga;

import java.util.Objects;
import pe.axiz.core.crypto.PayloadProtector;

/** The one boundary that knows both the context codec and the authoritative saga row identity. */
final class SagaContextStore<C> {
  private final String context;
  private final ContextCodec<C> codec;
  private final PayloadProtector protector;

  SagaContextStore(String sagaName, ContextCodec<C> codec, PayloadProtector protector) {
    this.context = "purpose:saga:" + Objects.requireNonNull(sagaName);
    this.codec = Objects.requireNonNull(codec);
    this.protector = Objects.requireNonNull(protector);
  }

  String toStored(String instanceId, C value) {
    return protector.protect(codec.encode(value), context, instanceId);
  }

  C fromStored(String instanceId, String stored) {
    return codec.decode(protector.reveal(stored, context, instanceId));
  }
}
