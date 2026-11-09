package pe.axiz.core.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Persistence port of the outbox. {@link #append} runs inside the business transaction. */
public interface OutboxStore {

  void append(OutboxRecord record);

  /**
   * Claims due entries atomically using database time for eligibility and lease expiration. The
   * lease starts no earlier than this call, never from an application clock or an older surrounding
   * transaction's timestamp. Implementations return the assigned owner and informational lease
   * expiration; subsequent updates must fence on that owner.
   */
  List<OutboxRecord> claimDue(int size, Duration lease, String owner);

  boolean markPublished(String id, String owner, Instant at);

  boolean markFailed(
      String id,
      String owner,
      int attempts,
      Instant nextAttemptAt,
      @Nullable String error,
      boolean giveUp);

  boolean retry(String id);

  boolean skip(String id, String reason);

  long countBlockedAggregates();

  long countSkipped();

  void verifySchema();

  /** Locks and returns up to {@code size} due entries (skipping entries locked by other relays). */
  List<OutboxRecord> lockDue(int size, Instant now);

  void markPublished(String id, Instant at);

  void markFailed(
      String id, int attempts, Instant nextAttemptAt, @Nullable String error, boolean giveUp);

  /** Deletes published entries older than the instant; returns how many were removed. */
  int purgePublished(Instant before);

  long countPending();
}
