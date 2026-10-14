package pe.axiz.spring.messaging;

import java.time.Duration;
import java.util.List;
import java.util.Map;
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
 * @param kafka MSK settings
 * @param sqs SQS consumers
 */
@ConfigurationProperties("axiz.messaging")
public record AxizMessagingProperties(
    @Nullable String source,
    @DefaultValue("") String typePrefix,
    @DefaultValue Map<String, String> destinations,
    @DefaultValue Consumer consumer,
    @DefaultValue Kafka kafka,
    @DefaultValue Sqs sqs) {

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
      @DefaultValue("10m") Duration deferMaxWait) {}

  /**
   * MSK.
   *
   * @param bootstrapServers broker list; defaults to {@code spring.kafka.bootstrap-servers}
   * @param iam whether IAM authentication is used (MSK)
   * @param topics topics consumed
   * @param groupId consumer group; defaults to the application name
   * @param concurrency listener threads
   * @param deadLetterSuffix suffix of the dead-letter topic
   * @param sendTimeout acknowledgement timeout of a publish
   */
  public record Kafka(
      @Nullable String bootstrapServers,
      @DefaultValue("true") boolean iam,
      @DefaultValue List<String> topics,
      @Nullable String groupId,
      @DefaultValue("1") int concurrency,
      @DefaultValue(".dlq") String deadLetterSuffix,
      @DefaultValue("10s") Duration sendTimeout) {}

  /**
   * SQS consumers (long polling on virtual threads).
   *
   * @param queues queue URLs consumed
   * @param deadLetterQueues dead-letter queue URL by source queue URL; without one, the queue's
   *     redrive policy applies
   * @param waitTime long-poll wait
   * @param batchSize messages per receive (1 to 10)
   * @param visibilityTimeout visibility of received messages, renewed every half of it while they
   *     wait in the batch or are being processed
   */
  public record Sqs(
      @DefaultValue List<String> queues,
      @DefaultValue Map<String, String> deadLetterQueues,
      @DefaultValue("20s") Duration waitTime,
      @DefaultValue("10") int batchSize,
      @DefaultValue("60s") Duration visibilityTimeout) {}
}
