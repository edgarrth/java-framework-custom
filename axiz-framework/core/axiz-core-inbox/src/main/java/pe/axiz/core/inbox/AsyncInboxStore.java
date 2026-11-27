package pe.axiz.core.inbox;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Puerto de inbox asíncrono nativo. Los tokens de propietario y la semántica del lease coinciden
 * con {@link InboxStore}.
 */
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

  /**
   * Las verificaciones de esquema terminan antes de que arranquen los consumidores; las
   * implementaciones deben preservar la cancelación.
   */
  default CompletionStage<Void> verifySchema() {
    return CompletableFuture.completedFuture(null);
  }
}
