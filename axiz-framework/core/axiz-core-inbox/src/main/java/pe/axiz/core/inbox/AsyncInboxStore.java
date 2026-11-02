package pe.axiz.core.inbox;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Native asynchronous inbox port. Owner tokens and lease semantics match {@link InboxStore}. */
public interface AsyncInboxStore {
  CompletionStage<InboxStore.Acquisition> tryAcquire(
      String consumer,
      String messageId,
      String owner,
      Instant now,
      Instant leaseUntil,
      Instant expiresAt);

  CompletionStage<Boolean> markProcessed(
      String consumer, String messageId, String owner, Instant at);

  CompletionStage<Boolean> release(String consumer, String messageId, String owner);

  CompletionStage<Integer> purgeExpired(Instant now);

  /** Schema checks complete before consumers start; implementations must preserve cancellation. */
  default CompletionStage<Void> verifySchema() {
    return CompletableFuture.completedFuture(null);
  }
}
