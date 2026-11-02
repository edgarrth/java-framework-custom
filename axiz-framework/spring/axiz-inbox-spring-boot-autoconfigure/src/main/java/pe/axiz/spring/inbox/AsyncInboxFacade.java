package pe.axiz.spring.inbox;

import java.time.Instant;
import java.util.Objects;
import pe.axiz.core.inbox.AsyncInboxStore;
import pe.axiz.core.inbox.InboxStore.Acquisition;
import pe.axiz.spring.foundation.ReactiveStages;
import reactor.core.publisher.Mono;

final class AsyncInboxFacade implements ReactiveInbox {
  private final AsyncInboxStore store;

  AsyncInboxFacade(AsyncInboxStore store) {
    this.store = Objects.requireNonNull(store);
  }

  @Override
  public Mono<Acquisition> tryAcquire(
      String consumer,
      String messageId,
      String owner,
      Instant now,
      Instant leaseUntil,
      Instant expiresAt) {
    return ReactiveStages.from(
        () -> store.tryAcquire(consumer, messageId, owner, now, leaseUntil, expiresAt));
  }

  @Override
  public Mono<Boolean> markProcessed(String consumer, String messageId, String owner, Instant at) {
    return ReactiveStages.from(() -> store.markProcessed(consumer, messageId, owner, at));
  }

  @Override
  public Mono<Boolean> release(String consumer, String messageId, String owner) {
    return ReactiveStages.from(() -> store.release(consumer, messageId, owner));
  }

  @Override
  public Mono<Integer> purgeExpired(Instant now) {
    return ReactiveStages.from(() -> store.purgeExpired(now));
  }

  @Override
  public Mono<Void> verifySchema() {
    return ReactiveStages.from(store::verifySchema);
  }
}
