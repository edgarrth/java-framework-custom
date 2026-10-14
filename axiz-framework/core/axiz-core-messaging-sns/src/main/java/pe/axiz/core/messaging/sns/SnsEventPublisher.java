package pe.axiz.core.messaging.sns;

import java.util.Map;
import java.util.Objects;
import pe.axiz.core.messaging.CloudEventJson;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventPublisher;
import pe.axiz.core.messaging.PublishException;
import pe.axiz.core.messaging.PublishResult;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;

/**
 * Publishes CloudEvents (structured JSON) to Amazon SNS topics (fan-out). The {@code ce_type}
 * attribute enables subscription filter policies. FIFO topics use the subject as group and the
 * event id as deduplication id. Destinations are topic ARNs.
 */
public final class SnsEventPublisher implements EventPublisher {

  private final SnsClient sns;

  public SnsEventPublisher(SnsClient sns) {
    this.sns = Objects.requireNonNull(sns);
  }

  @Override
  public boolean supports(String destination) {
    return destination.startsWith("arn:") && destination.contains(":sns:");
  }

  @Override
  public PublishResult publish(String destination, EventEnvelope event) {
    PublishRequest.Builder request =
        PublishRequest.builder()
            .topicArn(destination)
            .message(CloudEventJson.write(event))
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
      return new PublishResult(event.id(), destination, sns.publish(request.build()).messageId());
    } catch (SdkException e) {
      throw new PublishException("Event " + event.id() + " not accepted by " + destination, e);
    }
  }
}
