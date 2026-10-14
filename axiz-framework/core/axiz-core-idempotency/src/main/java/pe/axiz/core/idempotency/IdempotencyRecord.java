package pe.axiz.core.idempotency;

import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Stored state of an idempotency key.
 *
 * @param key scoped key (operation + caller + Idempotency-Key)
 * @param fingerprint hash of the request (method, path, query and body)
 * @param status processing status
 * @param owner token of the execution that holds the key; only that execution may complete or
 *     release it, so a request whose lock expired cannot overwrite the result of the next owner
 * @param response stored response once completed
 * @param expiresAt lock limit while in progress, retention limit once completed
 */
public record IdempotencyRecord(
    String key,
    String fingerprint,
    Status status,
    @Nullable String owner,
    @Nullable StoredResponse response,
    Instant expiresAt) {

  public IdempotencyRecord {
    Objects.requireNonNull(key);
    Objects.requireNonNull(fingerprint);
    Objects.requireNonNull(status);
    Objects.requireNonNull(expiresAt);
  }

  /** {@code true} when {@code candidate} holds this in-progress record. */
  public boolean heldBy(String candidate) {
    return status == Status.IN_PROGRESS && candidate.equals(owner);
  }

  /** Processing status. */
  public enum Status {
    IN_PROGRESS,
    COMPLETED
  }
}
