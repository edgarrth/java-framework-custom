package pe.axiz.core.outbox;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Persistence port of the outbox. {@link #append} runs inside the business transaction. */
public interface OutboxStore {

  void append(OutboxRecord record);

  /** Locks and returns up to {@code size} due entries (skipping entries locked by other relays). */
  List<OutboxRecord> lockDue(int size, Instant now);

  void markPublished(String id, Instant at);

  void markFailed(
      String id, int attempts, Instant nextAttemptAt, @Nullable String error, boolean giveUp);

  /** Deletes published entries older than the instant; returns how many were removed. */
  int purgePublished(Instant before);

  long countPending();
}
