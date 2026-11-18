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

  /** Retention deadline; expiry never means an execution lease can be taken over. */
  public Instant expiresAt() {
    return retainUntil;
  }

  public boolean liveAt(Instant now) {
    return retainUntil.isAfter(now);
  }

  public IdempotencyRecord completed(StoredResponse result, Instant until) {
    return IdempotencyRecord.builder(
            key, fingerprint, leaseUntil, until.isAfter(retainUntil) ? until : retainUntil)
        .status(Status.COMPLETED)
        .owner(owner)
        .response(result)
        .resolutionVersion(Math.addExact(resolutionVersion, 1))
        .failure(null)
        .build();
  }

  public IdempotencyRecord renewed(Instant lease, Instant until) {
    return IdempotencyRecord.builder(
            key,
            fingerprint,
            lease.isAfter(leaseUntil) ? lease : leaseUntil,
            until.isAfter(retainUntil) ? until : retainUntil)
        .status(status)
        .owner(owner)
        .response(response)
        .resolutionVersion(Math.addExact(resolutionVersion, 1))
        .failure(failure)
        .build();
  }

  public IdempotencyRecord unknown(String diagnostic) {
    return IdempotencyRecord.builder(key, fingerprint, leaseUntil, retainUntil)
        .status(Status.UNKNOWN)
        .owner(owner)
        .response(null)
        .resolutionVersion(Math.addExact(resolutionVersion, 1))
        .failure(diagnostic)
        .build();
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

  /** Named construction of a complete immutable stored snapshot. */
  public static Builder builder(
      String key, String fingerprint, Instant leaseUntil, Instant retainUntil) {
    return new Builder(key, fingerprint, leaseUntil, retainUntil);
  }

  /** Mutable construction state; build validates and creates an independent immutable value. */
  public static final class Builder {
    private final String key;
    private final String fingerprint;
    private final Instant leaseUntil;
    private final Instant retainUntil;
    private Status status = Status.IN_PROGRESS;
    private @Nullable String owner = null;
    private @Nullable StoredResponse response = null;
    private long resolutionVersion = 0;
    private @Nullable String failure = null;

    private Builder(String key, String fingerprint, Instant leaseUntil, Instant retainUntil) {
      this.key = Objects.requireNonNull(key);
      this.fingerprint = Objects.requireNonNull(fingerprint);
      this.leaseUntil = Objects.requireNonNull(leaseUntil);
      this.retainUntil = Objects.requireNonNull(retainUntil);
    }

    public Builder status(Status value) {
      this.status = value;
      return this;
    }

    public Builder owner(@Nullable String value) {
      this.owner = value;
      return this;
    }

    public Builder response(@Nullable StoredResponse value) {
      this.response = value;
      return this;
    }

    public Builder resolutionVersion(long value) {
      this.resolutionVersion = value;
      return this;
    }

    public Builder failure(@Nullable String value) {
      this.failure = value;
      return this;
    }

    public IdempotencyRecord build() {
      return new IdempotencyRecord(
          key,
          fingerprint,
          status,
          owner,
          response,
          leaseUntil,
          retainUntil,
          resolutionVersion,
          failure);
    }
  }
}
