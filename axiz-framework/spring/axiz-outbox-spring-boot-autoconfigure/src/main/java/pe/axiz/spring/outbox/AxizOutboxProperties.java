package pe.axiz.spring.outbox;

import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Shared relay policy; persistence settings belong to the selected integration. */
@ConfigurationProperties("axiz.outbox")
public record AxizOutboxProperties(@DefaultValue("jdbc") String store, @DefaultValue Relay relay) {
  /**
   * Relay to the broker (at-least-once; safe with several replicas thanks to {@code SKIP LOCKED}).
   *
   * @param enabled whether this instance relays
   * @param pollInterval wait between polls when the outbox is empty
   * @param batchSize records per transaction
   * @param maxAttempts attempts before a record is marked failed
   * @param initialDelay first retry delay
   * @param maxDelay retry delay cap
   * @param retention how long published records are kept
   * @param purgeInterval how often published records are purged
   */
  public record Relay(
      @DefaultValue("true") boolean enabled,
      @DefaultValue("1s") Duration pollInterval,
      @DefaultValue("100") int batchSize,
      @DefaultValue("10") int maxAttempts,
      @DefaultValue("1s") Duration initialDelay,
      @DefaultValue("10m") Duration maxDelay,
      @DefaultValue("7d") Duration retention,
      @DefaultValue("1h") Duration purgeInterval,
      @DefaultValue("3m") Duration leaseDuration,
      @DefaultValue("2s") Duration claimAllowance,
      @Nullable Duration publishBudget) {
    @ConstructorBinding
    public Relay {
      if (leaseDuration.toNanos() <= 0)
        throw new IllegalArgumentException("outbox lease must be positive");
      if (claimAllowance.toNanos() <= 0 || (publishBudget != null && publishBudget.toNanos() <= 0))
        throw new IllegalArgumentException(
            "outbox claim-allowance and publish-budget must be positive");
    }

    /** Named construction; required values and invariants are checked by build. */
    public static Builder builder() {
      return new Builder();
    }

    /** Mutable construction state; use one builder per operation, never as shared state. */
    public static final class Builder {
      private boolean enabled = false;
      private @Nullable Duration pollInterval = null;
      private int batchSize = 0;
      private int maxAttempts = 0;
      private @Nullable Duration initialDelay = null;
      private @Nullable Duration maxDelay = null;
      private @Nullable Duration retention = null;
      private @Nullable Duration purgeInterval = null;
      private @Nullable Duration leaseDuration = null;
      private @Nullable Duration claimAllowance = null;
      private @Nullable Duration publishBudget = null;

      private Builder() {}

      public Builder enabled(boolean value) {
        this.enabled = value;
        return this;
      }

      public Builder pollInterval(Duration value) {
        this.pollInterval = value;
        return this;
      }

      public Builder batchSize(int value) {
        this.batchSize = value;
        return this;
      }

      public Builder maxAttempts(int value) {
        this.maxAttempts = value;
        return this;
      }

      public Builder initialDelay(Duration value) {
        this.initialDelay = value;
        return this;
      }

      public Builder maxDelay(Duration value) {
        this.maxDelay = value;
        return this;
      }

      public Builder retention(Duration value) {
        this.retention = value;
        return this;
      }

      public Builder purgeInterval(Duration value) {
        this.purgeInterval = value;
        return this;
      }

      public Builder leaseDuration(Duration value) {
        this.leaseDuration = value;
        return this;
      }

      public Builder claimAllowance(Duration value) {
        this.claimAllowance = value;
        return this;
      }

      public Builder publishBudget(@Nullable Duration value) {
        this.publishBudget = value;
        return this;
      }

      public Relay build() {
        return new Relay(
            enabled,
            Objects.requireNonNull(pollInterval, "pollInterval"),
            batchSize,
            maxAttempts,
            Objects.requireNonNull(initialDelay, "initialDelay"),
            Objects.requireNonNull(maxDelay, "maxDelay"),
            Objects.requireNonNull(retention, "retention"),
            Objects.requireNonNull(purgeInterval, "purgeInterval"),
            Objects.requireNonNull(leaseDuration, "leaseDuration"),
            Objects.requireNonNull(claimAllowance, "claimAllowance"),
            publishBudget);
      }
    }
  }
}
