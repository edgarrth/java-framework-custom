package pe.axiz.core.idempotency;

import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Storage of idempotency records (Redis or DynamoDB in production).
 *
 * <p>Every write after {@link #tryStart} is fenced by the owner token of the record: an execution
 * whose retention expired and whose key was acquired again cannot complete or release the new
 * owner's key.
 */
public interface IdempotencyStore {

  /** Atomic full-record comparison; owner and version fence stale writers, including key reuse. */
  boolean compareAndSet(IdempotencyRecord expected, @Nullable IdempotencyRecord replacement);

  default boolean renew(
      String key, String owner, Instant leaseUntil, Instant retainUntil, Instant now) {
    for (int attempt = 0; attempt < 16; attempt++) {
      Optional<IdempotencyRecord> found = find(key, now);
      if (found.isEmpty()) return false;
      IdempotencyRecord current = found.get();
      if (!current.heldBy(owner) || !current.leaseUntil().isAfter(now)) return false;
      if (compareAndSet(current, current.renewed(leaseUntil, retainUntil))) return true;
    }
    return false;
  }

  default boolean markUnknown(String key, String owner, String diagnostic, Instant now) {
    for (int attempt = 0; attempt < 16; attempt++) {
      Optional<IdempotencyRecord> found = find(key, now);
      if (found.isEmpty() || !found.get().heldBy(owner)) return false;
      if (compareAndSet(found.get(), found.get().unknown(diagnostic))) return true;
    }
    return false;
  }

  default boolean releaseWithoutEffect(String key, String owner) {
    return release(key, owner);
  }

  /** Resolution applies only to the generation and version the application actually inspected. */
  default boolean resolve(
      IdempotencyRecord expected, Resolution resolution, Instant now, Instant retainUntil) {
    if (!expected.liveAt(now)
        || expected.status() == IdempotencyRecord.Status.COMPLETED
        || (expected.status() == IdempotencyRecord.Status.IN_PROGRESS
            && expected.leaseUntil().isAfter(now))) {
      return false;
    }
    StoredResponse response = resolution.response();
    return compareAndSet(
        expected, response == null ? null : expected.completed(response, retainUntil));
  }

  /**
   * Atomically creates the IN_PROGRESS record when the key is free (absent or past retention).
   * Lease expiry alone never frees the key.
   *
   * @param record record with its owner token
   * @return {@code false} when another live record holds the key
   */
  boolean tryStart(IdempotencyRecord record);

  Optional<IdempotencyRecord> find(String key, Instant now);

  /**
   * Stores the response, only if the key is still IN_PROGRESS and held by {@code owner}.
   *
   * @return {@code false} when the owner lost the key (retention expired and key acquired by
   *     another request)
   */
  boolean complete(String key, String owner, StoredResponse response, Instant expiresAt);

  /**
   * Deletes the IN_PROGRESS record after a failure so the client can retry, only if it is held by
   * {@code owner}. Completed records are kept.
   *
   * @return {@code false} when there was nothing of this owner to release
   */
  boolean release(String key, String owner);
}
