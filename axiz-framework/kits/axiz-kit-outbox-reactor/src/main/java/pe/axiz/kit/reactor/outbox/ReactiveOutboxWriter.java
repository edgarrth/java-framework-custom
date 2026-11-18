package pe.axiz.kit.reactor.outbox;

import org.jspecify.annotations.Nullable;
import pe.axiz.core.messaging.EventEnvelope;
import reactor.core.publisher.Mono;

/**
 * Cold, native write joined to the caller's Reactor transaction. Implementations must preserve
 * subscriber context, cancellation and errors; a CompletionStage bridge cannot carry this contract.
 * No database driver or store implementation is part of the port.
 */
@FunctionalInterface
public interface ReactiveOutboxWriter {
  /** Borrowed resource identity; implementations that cannot attest it return null. */
  default @Nullable Object transactionResource() {
    return null;
  }

  Mono<Void> write(String destination, EventEnvelope event, @Nullable String aggregateType);
}
