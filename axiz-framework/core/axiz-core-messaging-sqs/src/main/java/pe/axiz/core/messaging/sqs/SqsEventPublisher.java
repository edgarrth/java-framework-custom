package pe.axiz.core.messaging.sqs;

import java.util.Map;
import java.util.Objects;
import pe.axiz.core.messaging.CloudEventJson;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventPublisher;
import pe.axiz.core.messaging.PublishException;
import pe.axiz.core.messaging.PublishResult;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * Publishes CloudEvents (structured JSON) to Amazon SQS. FIFO queues use the subject as message
 * group and the event id as deduplication id. Destinations are queue URLs.
 */
public final class SqsEventPublisher implements EventPublisher {

  private final SqsClient sqs;

  public SqsEventPublisher(SqsClient sqs) {
    this.sqs = Objects.requireNonNull(sqs);
  }

  @Override
  public boolean supports(String destination) {
    return destination.startsWith("https://") && destination.contains("sqs");
  }

  @Override
  public PublishResult publish(String destination, EventEnvelope event) {
    SendMessageRequest.Builder request =
        SendMessageRequest.builder()
            .queueUrl(destination)
            .messageBody(CloudEventJson.write(event))
            .messageAttributes(
                Map.of(
                    "ce_type",
                    MessageAttributeValue.builder()
                        .dataType("String")
                        .stringValue(event.type())
                        .build()));
    if (destination.endsWith(".fifo")) {
      request
          .messageGroupId(event.subject() != null ? event.subject() : event.type())
          .messageDeduplicationId(event.id());
    }
    try {
      return new PublishResult(
          event.id(), destination, sqs.sendMessage(request.build()).messageId());
    } catch (SdkException e) {
      throw new PublishException("Event " + event.id() + " not accepted by " + destination, e);
    }
  }
}
