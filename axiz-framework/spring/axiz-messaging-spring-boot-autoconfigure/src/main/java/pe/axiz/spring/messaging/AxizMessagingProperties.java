package pe.axiz.spring.messaging;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Messaging ({@code axiz.messaging.*}): CloudEvents 1.0 envelopes over MSK, SQS and SNS.
 *
 * @param source CloudEvents {@code source}; defaults to {@code urn:axiz:<application>}
 * @param typePrefix prefix of event types, e.g. {@code pe.axiz.payments.}
 * @param destinations logical destination name to physical address ({@code kafka:<topic>}, SQS
 *     queue URL or SNS topic ARN)
 * @param consumer consumption settings
 */
@ConfigurationProperties("axiz.messaging")
public record AxizMessagingProperties(
    @Nullable String source,
    @DefaultValue("") String typePrefix,
    @DefaultValue Map<String, String> destinations,
    @DefaultValue Consumer consumer,
    @DefaultValue Routes routes) {
  public AxizMessagingProperties {
    destinations = Collections.unmodifiableMap(new LinkedHashMap<>(destinations));
  }

  /**
   * Physical address of a logical destination (or the value itself when it is already physical).
   */
  public String resolve(String destination) {
    return destinations.getOrDefault(destination, destination);
  }

  /**
   * Consumption.
   *
   * @param name consumer name used for de-duplication; defaults to the application name
   * @param maxAttempts deliveries before dead-lettering
   * @param initialDelay first redelivery delay
   * @param maxDelay redelivery delay cap
   * @param ignoreUnknownTypes whether events without handler are acknowledged instead of
   *     dead-lettered
   * @param subscriptions event types that must have a handler (checked at startup)
   * @param deferInterval wait between redeliveries of a message another delivery is processing
   *     (valid inbox lease)
   * @param deferMaxWait how long such a message keeps being redelivered before it follows the
   *     failure path; keep it above {@code axiz.inbox.lease}
   */
  public record Consumer(
      @Nullable String name,
      @DefaultValue("5") int maxAttempts,
      @DefaultValue("1s") Duration initialDelay,
      @DefaultValue("5m") Duration maxDelay,
      @DefaultValue("false") boolean ignoreUnknownTypes,
      @DefaultValue List<String> subscriptions,
      @DefaultValue("10s") Duration deferInterval,
      @DefaultValue("10m") Duration deferMaxWait,
      @DefaultValue("true") boolean transactional,
      @DefaultValue("30s") Duration handlerTimeout) {
    public Consumer {
      subscriptions = List.copyOf(subscriptions);
    }

    /** Named construction; required values and invariants are checked by build. */
    public static Builder builder() {
      return new Builder();
    }

    /** Mutable construction state; use one builder per operation, never as shared state. */
    public static final class Builder {
      private @Nullable String name = null;
      private int maxAttempts = 0;
      private @Nullable Duration initialDelay = null;
      private @Nullable Duration maxDelay = null;
      private boolean ignoreUnknownTypes = false;
      private @Nullable List<String> subscriptions = List.of();
      private @Nullable Duration deferInterval = null;
      private @Nullable Duration deferMaxWait = null;
      private boolean transactional = false;
      private @Nullable Duration handlerTimeout = null;

      private Builder() {}

      public Builder name(@Nullable String value) {
        this.name = value;
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

      public Builder ignoreUnknownTypes(boolean value) {
        this.ignoreUnknownTypes = value;
        return this;
      }

      public Builder subscriptions(List<String> value) {
        this.subscriptions = List.copyOf(value);
        return this;
      }

      public Builder deferInterval(Duration value) {
        this.deferInterval = value;
        return this;
      }

      public Builder deferMaxWait(Duration value) {
        this.deferMaxWait = value;
        return this;
      }

      public Builder transactional(boolean value) {
        this.transactional = value;
        return this;
      }

      public Builder handlerTimeout(Duration value) {
        this.handlerTimeout = value;
        return this;
      }

      public Consumer build() {
        return new Consumer(
            name,
            maxAttempts,
            Objects.requireNonNull(initialDelay, "initialDelay"),
            Objects.requireNonNull(maxDelay, "maxDelay"),
            ignoreUnknownTypes,
            Objects.requireNonNull(subscriptions, "subscriptions"),
            Objects.requireNonNull(deferInterval, "deferInterval"),
            Objects.requireNonNull(deferMaxWait, "deferMaxWait"),
            transactional,
            Objects.requireNonNull(handlerTimeout, "handlerTimeout"));
      }
    }
  }

  /** Outbound event routing, independent of the service structure. */
  public record Routes(
      @DefaultValue("domain-events") String defaultDestination,
      @DefaultValue Map<String, String> byType) {
    public Routes {
      byType = Map.copyOf(byType);
    }
  }
}
