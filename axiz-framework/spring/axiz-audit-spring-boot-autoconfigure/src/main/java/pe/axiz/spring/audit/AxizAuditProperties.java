package pe.axiz.spring.audit;

import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Audit settings ({@code axiz.audit.*}).
 *
 * @param async asynchronous publication with a bounded queue on a virtual thread
 * @param http audit of inbound HTTP requests
 * @param kafka publication to an MSK topic
 * @param sqs publication to an SQS queue
 * @param durable audit of business changes written to the outbox in their transaction
 */
@ConfigurationProperties("axiz.audit")
public record AxizAuditProperties(
    @DefaultValue Async async,
    @DefaultValue Http http,
    @DefaultValue Kafka kafka,
    @DefaultValue Sqs sqs,
    @DefaultValue Durable durable,
    @Nullable Mode mode) {
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

  /**
   * Kafka (MSK) sink.
   *
   * @param topic topic; the sink is active only when set
   * @param timeout send acknowledgement timeout
   */
  public record Kafka(@Nullable String topic, @DefaultValue("5s") Duration timeout) {
    public Kafka {
      if (topic != null && (topic.isBlank() || topic.contains("${")))
        throw new IllegalArgumentException(
            "axiz.audit.kafka.topic must not be blank or unresolved when configured");
      if (!timeout.isPositive())
        throw new IllegalArgumentException("Audit Kafka timeout must be positive");
    }
  }

  /**
   * SQS sink.
   *
   * @param queueUrl queue URL; the sink is active only when set
   */
  public record Sqs(@Nullable String queueUrl) {
    public Sqs {
      if (queueUrl != null && (queueUrl.isBlank() || queueUrl.contains("${")))
        throw new IllegalArgumentException(
            "axiz.audit.sqs.queue-url must not be blank or unresolved when configured");
    }
  }
}
