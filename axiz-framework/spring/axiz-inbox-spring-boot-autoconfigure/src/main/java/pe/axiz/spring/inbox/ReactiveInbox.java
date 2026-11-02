package pe.axiz.spring.inbox;

import java.time.Instant;
import pe.axiz.core.inbox.InboxStore.Acquisition;
import reactor.core.publisher.Mono;

/**
 * Deferred, cancellable inbox facade for application flows; synchronous stores serve poll workers.
 */
public interface ReactiveInbox {
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
