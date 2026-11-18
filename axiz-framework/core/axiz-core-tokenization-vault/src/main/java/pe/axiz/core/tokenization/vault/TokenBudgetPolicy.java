package pe.axiz.core.tokenization.vault;

import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Explicit server-owned limits; no permissive defaults or request-provided budget dimensions. */
public record TokenBudgetPolicy(
    Limits query,
    Limits reveal,
    Limits metadata,
    Limits writes,
    int subjectConcurrent,
    int clientConcurrent,
    int tenantConcurrent,
    Duration concurrencyRetryAfter) {
  /** Each dimension has independent one-second burst, one-minute and 24-hour windows. */
  public record Windows(long burst, long minute, long day) {
    public Windows {
      if (burst < 1 || minute < burst || day < minute || day > 1_000_000_000L)
        throw new IllegalArgumentException("Invalid token budget windows");
    }
  }

  /** A subject cannot reset its budget by changing clients; the client has its own aggregate. */
  public record Limits(Windows subject, Windows client, Windows tenant) {
    public Limits {
      Objects.requireNonNull(subject);
      Objects.requireNonNull(client);
      Objects.requireNonNull(tenant);
    }
  }

  public TokenBudgetPolicy {
    Objects.requireNonNull(query);
    Objects.requireNonNull(reveal);
    Objects.requireNonNull(metadata);
    Objects.requireNonNull(writes);
    Objects.requireNonNull(concurrencyRetryAfter);
    if (subjectConcurrent < 1
        || clientConcurrent < 1
        || tenantConcurrent < 1
        || subjectConcurrent > 1_000_000
        || clientConcurrent > 1_000_000
        || tenantConcurrent > 1_000_000
        || concurrencyRetryAfter.compareTo(Duration.ofSeconds(1)) < 0
        || concurrencyRetryAfter.compareTo(Duration.ofMinutes(1)) > 0)
      throw new IllegalArgumentException("Invalid token concurrency policy");
  }

  /** Named construction; required values and invariants are checked by build. */
  public static Builder builder() {
    return new Builder();
  }

  /** Mutable construction state; use one builder per operation, never as shared state. */
  public static final class Builder {
    private @Nullable Limits query = null;
    private @Nullable Limits reveal = null;
    private @Nullable Limits metadata = null;
    private @Nullable Limits writes = null;
    private int subjectConcurrent = 0;
    private int clientConcurrent = 0;
    private int tenantConcurrent = 0;
    private @Nullable Duration concurrencyRetryAfter = null;

    private Builder() {}

    public Builder query(Limits value) {
      this.query = value;
      return this;
    }

    public Builder reveal(Limits value) {
      this.reveal = value;
      return this;
    }

    public Builder metadata(Limits value) {
      this.metadata = value;
      return this;
    }

    public Builder writes(Limits value) {
      this.writes = value;
      return this;
    }

    public Builder subjectConcurrent(int value) {
      this.subjectConcurrent = value;
      return this;
    }

    public Builder clientConcurrent(int value) {
      this.clientConcurrent = value;
      return this;
    }

    public Builder tenantConcurrent(int value) {
      this.tenantConcurrent = value;
      return this;
    }

    public Builder concurrencyRetryAfter(Duration value) {
      this.concurrencyRetryAfter = value;
      return this;
    }

    public TokenBudgetPolicy build() {
      return new TokenBudgetPolicy(
          Objects.requireNonNull(query, "query"),
          Objects.requireNonNull(reveal, "reveal"),
          Objects.requireNonNull(metadata, "metadata"),
          Objects.requireNonNull(writes, "writes"),
          subjectConcurrent,
          clientConcurrent,
          tenantConcurrent,
          Objects.requireNonNull(concurrencyRetryAfter, "concurrencyRetryAfter"));
    }
  }
}
