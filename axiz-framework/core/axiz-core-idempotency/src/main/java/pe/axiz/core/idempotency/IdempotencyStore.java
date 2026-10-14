package pe.axiz.core.idempotency;

import java.time.Instant;
import java.util.Optional;

/**
 * Storage of idempotency records (Redis or DynamoDB in production).
 *
 * <p>Every write after {@link #tryStart} is fenced by the owner token of the record: an execution
 * whose lock expired and was taken by another request can no longer complete or release the key.
 */
public interface IdempotencyStore {

  /**
   * Atomically creates the IN_PROGRESS record when the key is free (absent or expired).
   *
   * @param record record with its owner token
   * @return {@code false} when another live record holds the key
   */
  boolean tryStart(IdempotencyRecord record);

  Optional<IdempotencyRecord> find(String key, Instant now);

  /**
   * Stores the response, only if the key is still IN_PROGRESS and held by {@code owner}.
   *
   * @return {@code false} when the owner lost the key (expired lock, taken by another request)
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
