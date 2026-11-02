package pe.axiz.adapter.inbox.r2dbc;

import java.time.Instant;
import pe.axiz.core.inbox.InboxStore.Acquisition;
import reactor.core.publisher.Mono;

/** Native R2DBC inbox contract; Reactor preserves the subscription and transaction context. */
public interface ReactiveInboxStore {
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
