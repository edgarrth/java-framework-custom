package pe.axiz.quality.stores;

import java.time.Instant;
import java.util.concurrent.CompletionStage;
import pe.axiz.adapter.inbox.r2dbc.ReactiveInboxStore;
import pe.axiz.core.inbox.AsyncInboxStore;
import pe.axiz.core.inbox.InboxStore.Acquisition;

/**
 * Un puente exclusivo de pruebas permite que ambos contratos nativos ejecuten las mismas aserciones
 * de propiedad y retención.
 */
final class NativeInboxContractAdapters {
  private NativeInboxContractAdapters() {}

  static AsyncInboxStore r2dbc(ReactiveInboxStore store) {
    return new AsyncInboxStore() {
      public CompletionStage<Acquisition> tryAcquire(
          String consumer,
          String messageId,
          String owner,
          Instant now,
          Instant leaseUntil,
          Instant expiresAt) {
        return store.tryAcquire(consumer, messageId, owner, now, leaseUntil, expiresAt).toFuture();
      }

      public CompletionStage<Boolean> markProcessed(
          String consumer, String messageId, String owner, Instant at) {
        return store.markProcessed(consumer, messageId, owner, at).toFuture();
      }

      public CompletionStage<Boolean> release(String consumer, String messageId, String owner) {
        return store.release(consumer, messageId, owner).toFuture();
      }

      public CompletionStage<Integer> purgeExpired(Instant now) {
        return store.purgeExpired(now).toFuture();
      }

      public CompletionStage<Void> verifySchema() {
        return store.verifySchema().toFuture();
      }
    };
  }
}
