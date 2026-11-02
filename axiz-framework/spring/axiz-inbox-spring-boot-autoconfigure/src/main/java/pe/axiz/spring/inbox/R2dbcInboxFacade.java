package pe.axiz.spring.inbox;

import java.time.Instant;
import java.util.Objects;
import pe.axiz.adapter.inbox.r2dbc.ReactiveInboxStore;
import pe.axiz.core.inbox.InboxStore.Acquisition;
import reactor.core.publisher.Mono;

/** Keeps R2DBC's subscription context rather than crossing a CompletableFuture boundary. */
final class R2dbcInboxFacade implements ReactiveInbox {
  private final ReactiveInboxStore store;

  R2dbcInboxFacade(ReactiveInboxStore store) {
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
    return Mono.defer(
        () -> store.tryAcquire(consumer, messageId, owner, now, leaseUntil, expiresAt));
  }

  @Override
  public Mono<Boolean> markProcessed(String consumer, String messageId, String owner, Instant at) {
    return Mono.defer(() -> store.markProcessed(consumer, messageId, owner, at));
  }

  @Override
  public Mono<Boolean> release(String consumer, String messageId, String owner) {
    return Mono.defer(() -> store.release(consumer, messageId, owner));
  }

  @Override
  public Mono<Integer> purgeExpired(Instant now) {
    return Mono.defer(() -> store.purgeExpired(now));
  }

  @Override
  public Mono<Void> verifySchema() {
    return Mono.defer(store::verifySchema);
  }
}
