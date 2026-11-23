package pe.axiz.adapter.inbox.r2dbc;

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.inbox.InboxStore.Acquisition;
import reactor.core.publisher.Mono;

/** Contrato nativo de inbox R2DBC; Reactor preserva la suscripción y el contexto transaccional. */
public interface ReactiveInboxStore {
  default @Nullable Object transactionResource() {
    return null;
  }

  Mono<Acquisition> tryAcquire(
      String consumer,
      String messageId,
      String owner,
      Instant now,
      Instant leaseUntil,
      Instant expiresAt);

  Mono<Boolean> markProcessed(String consumer, String messageId, String owner, Instant at);

  Mono<Boolean> release(String consumer, String messageId, String owner);

  Mono<Integer> purgeExpired(Instant now);

  Mono<Void> verifySchema();
}
