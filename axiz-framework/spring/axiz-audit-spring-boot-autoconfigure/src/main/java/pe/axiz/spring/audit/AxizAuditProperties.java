package pe.axiz.spring.audit;

import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
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
    @DefaultValue Durable durable) {

  /**
   * Asynchronous publication.
   *
   * @param enabled whether events are queued and published by a background virtual thread
   * @param capacity queue capacity; when full, events go to the log publisher instead of being lost
   * @param offerTimeout how long a caller waits for queue space
   */
  public record Async(
      @DefaultValue("true") boolean enabled,
      @DefaultValue("10000") int capacity,
      @DefaultValue("50ms") Duration offerTimeout) {}

  /**
   * Durable audit for financial evidence: successful actions recorded inside a transaction are
   * written to the outbox with the business change (needs the outbox capability) and published by
   * its relay; nothing is lost if the process dies after the commit.
   *
   * @param enabled whether audited successes go through the outbox
   * @param destination logical outbox destination of audit events (see {@code
   *     axiz.messaging.destinations})
   * @param outsideTransaction {@code async}: successes recorded outside a transaction use the
   *     asynchronous publisher; {@code fail}: they are refused
   */
  public record Durable(
      @DefaultValue("false") boolean enabled,
      @DefaultValue("audit") String destination,
      @DefaultValue("async") AuditOutsideTransaction outsideTransaction) {}

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
  public record Kafka(@Nullable String topic, @DefaultValue("5s") Duration timeout) {}

  /**
   * SQS sink.
   *
   * @param queueUrl queue URL; the sink is active only when set
   */
  public record Sqs(@Nullable String queueUrl) {}
}
