package pe.axiz.core.messaging;

/**
 * Publishes events and waits for the broker confirmation. Implementations are stateless and
 * thread-safe (no state shared between sends).
 */
public interface EventPublisher {

  /**
   * Publishes the event.
   *
   * @param destination logical destination resolved by the adapter (topic, queue URL, topic ARN)
   * @param event the event
   * @return the confirmation
   * @throws PublishException when the broker does not confirm
   */
  PublishResult publish(String destination, EventEnvelope event);

  /** Whether this publisher handles the destination (used to route among MSK, SQS and SNS). */
  default boolean supports(String destination) {
    return true;
  }
}
