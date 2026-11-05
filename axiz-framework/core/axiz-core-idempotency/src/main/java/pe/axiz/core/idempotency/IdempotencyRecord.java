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
 * @param leaseUntil execution lease deadline, independent of retention
 * @param retainUntil deduplication retention deadline
 * @param resolutionVersion optimistic version, fenced by the execution owner across key reuse
 * @param failure bounded diagnostic without request or integration content
 */
public record IdempotencyRecord(
    String key,
    String fingerprint,
    Status status,
    @Nullable String owner,
    @Nullable StoredResponse response,
    Instant leaseUntil,
    Instant retainUntil,
    long resolutionVersion,
    @Nullable String failure) {

  public IdempotencyRecord {
    Objects.requireNonNull(key);
    Objects.requireNonNull(fingerprint);
    Objects.requireNonNull(status);
    Objects.requireNonNull(leaseUntil);
    Objects.requireNonNull(retainUntil);
    if (resolutionVersion < 0) throw new IllegalArgumentException("negative version");
    if (failure != null && failure.length() > 256)
      throw new IllegalArgumentException("diagnostic too long");
  }

  /** Constructor for existing integrations; new executions must separate lease and retention. */
  public IdempotencyRecord(
      String key,
      String fingerprint,
      Status status,
      @Nullable String owner,
      @Nullable StoredResponse response,
      Instant expiresAt) {
    this(key, fingerprint, status, owner, response, expiresAt, expiresAt, 0, null);
  }

  /** Retention deadline; expiry never means an execution lease can be taken over. */
  public Instant expiresAt() {
    return retainUntil;
  }

  public boolean liveAt(Instant now) {
    return retainUntil.isAfter(now);
  }

  public IdempotencyRecord completed(StoredResponse result, Instant until) {
    return new IdempotencyRecord(
        key,
        fingerprint,
        Status.COMPLETED,
        owner,
        result,
        leaseUntil,
        until.isAfter(retainUntil) ? until : retainUntil,
        Math.addExact(resolutionVersion, 1),
        null);
  }

  public IdempotencyRecord renewed(Instant lease, Instant until) {
    return new IdempotencyRecord(
        key,
        fingerprint,
        status,
        owner,
        response,
        lease.isAfter(leaseUntil) ? lease : leaseUntil,
        until.isAfter(retainUntil) ? until : retainUntil,
        Math.addExact(resolutionVersion, 1),
        failure);
  }

  public IdempotencyRecord unknown(String diagnostic) {
    return new IdempotencyRecord(
        key,
        fingerprint,
        Status.UNKNOWN,
        owner,
        null,
        leaseUntil,
        retainUntil,
        Math.addExact(resolutionVersion, 1),
        diagnostic);
  }

  /** {@code true} when {@code candidate} holds this in-progress record. */
  public boolean heldBy(String candidate) {
    return status == Status.IN_PROGRESS && candidate.equals(owner);
  }

  /** Processing status. */
  public enum Status {
    IN_PROGRESS,
    COMPLETED,
    UNKNOWN
  }
}
