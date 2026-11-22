package pe.axiz.spring.audit;

import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import pe.axiz.core.audit.AuditSealer;

/**
 * Audit settings ({@code axiz.audit.*}).
 *
 * @param async asynchronous publication with a bounded queue on a virtual thread
 * @param http audit of inbound HTTP requests
 * @param durable audit of business changes written to the outbox in their transaction
 */
@ConfigurationProperties("axiz.audit")
public record AxizAuditProperties(
    @DefaultValue Async async,
    @DefaultValue Http http,
    @DefaultValue Durable durable,
    @DefaultValue Integrity integrity,
    @DefaultValue Strict strict,
    @Nullable Mode mode) {
  /**
   * Hash chains by default in local/test/ci; every other environment must use HMAC, with an
   * independent Base64 secret and an explicit rotation id (an unkeyed chain can be recomputed).
   */
  public record Integrity(
      @DefaultValue("HASH") AuditSealer.Mode mode,
      @Nullable String hmacSecret,
      @Nullable String hmacKeyId) {
    public Integrity {
      if (mode == AuditSealer.Mode.HMAC) {
        if (hmacSecret == null
            || hmacSecret.isBlank()
            || hmacSecret.contains("${")
            || hmacKeyId == null
            || !hmacKeyId.matches("[A-Za-z0-9_-]{1,64}"))
          throw new IllegalArgumentException(
              "Audit HMAC requires hmac-secret and a valid hmac-key-id");
      } else if (hmacSecret != null || hmacKeyId != null) {
        throw new IllegalArgumentException("Audit HMAC settings require integrity.mode=HMAC");
      }
    }
  }

  /** Independent confirmed path; it never reuses a queue or tolerant publication fallback. */
  public record Strict(
      @DefaultValue("false") boolean enabled,
      @DefaultValue("DIRECT") StrictMode mode,
      @DefaultValue("2s") Duration timeout,
      @DefaultValue("4") int concurrency,
      @DefaultValue("1m") Duration checkpointInterval) {
    public Strict {
      if (!timeout.isPositive()
          || timeout.compareTo(Duration.ofMinutes(1)) > 0
          || concurrency < 1
          || concurrency > 64
          || checkpointInterval.compareTo(Duration.ofSeconds(1)) < 0
          || checkpointInterval.compareTo(Duration.ofDays(1)) > 0)
        throw new IllegalArgumentException(
            "Invalid strict audit deadline, concurrency or checkpoint interval");
    }
  }

  /** DIRECT requires an attested destination; DURABLE requires an independent outbox commit. */
  public enum StrictMode {
    DIRECT,
    DURABLE
  }

  /** Delivery and persistence guarantee for audit events. */
  public enum Mode {
    ASYNC,
    DURABLE,
    SYNC
  }

  public Mode effectiveMode() {
    return mode == null ? Mode.ASYNC : mode;
  }

  /**
   * Asynchronous publication.
   *
   * @param capacity queue capacity; when full, events go to the log publisher instead of being lost
   * @param offerTimeout how long a caller waits for queue space
   */
  public record Async(
      @DefaultValue("10000") int capacity,
      @DefaultValue("50ms") Duration offerTimeout,
      @DefaultValue("10s") Duration deliveryTimeout) {
    @ConstructorBinding
    public Async {
      if (capacity < 1 || offerTimeout.isNegative() || !deliveryTimeout.isPositive())
        throw new IllegalArgumentException("Invalid audit queue bounds");
    }
  }

  /**
   * Durable audit for financial evidence: successful actions recorded inside a transaction are
   * written to the outbox with the business change (needs the outbox capability) and published by
   * its relay; nothing is lost if the process dies after the commit.
   *
   * @param destination logical outbox destination of audit events (see {@code
   *     axiz.messaging.destinations})
   * @param outsideTransaction {@code async}: successes recorded outside a transaction use the
   *     asynchronous publisher; {@code fail}: they are refused
   */
  public record Durable(
      @DefaultValue("audit") String destination,
      @DefaultValue("async") AuditOutsideTransaction outsideTransaction,
      @DefaultValue("2s") Duration failureTimeout,
      @DefaultValue("4") int failureConcurrency) {
    @ConstructorBinding
    public Durable {
      if (destination == null || destination.isBlank() || destination.contains("${"))
        throw new IllegalArgumentException("Audit durable destination must not be blank");
      if (!failureTimeout.isPositive() || failureConcurrency < 1)
        throw new IllegalArgumentException("Invalid durable failure timeout or concurrency");
    }
  }

  /**
   * HTTP audit.
   *
   * @param enabled whether requests are audited
   * @param methods audited methods
   * @param includedPaths path patterns audited
   * @param excludedPaths path patterns never audited
   */
  public record Http(
      @DefaultValue("false") boolean enabled,
      @DefaultValue({"POST", "PUT", "PATCH", "DELETE"}) List<String> methods,
      @DefaultValue("/**") List<String> includedPaths,
      @DefaultValue("/actuator/**") List<String> excludedPaths) {
    public Http {
      excludedPaths = List.copyOf(excludedPaths);
      includedPaths = List.copyOf(includedPaths);
      methods = List.copyOf(methods);
    }
  }
}
