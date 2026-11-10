package pe.axiz.adapter.messaging.sqs;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import pe.axiz.core.messaging.BoundedPublisher;
import pe.axiz.core.messaging.CloudEventJson;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventPublisher;
import pe.axiz.core.messaging.PublishBound;
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
public final class SqsEventPublisher implements EventPublisher, BoundedPublisher {

  private final SqsClient sqs;

  public SqsEventPublisher(SqsClient sqs) {
    this.sqs = Objects.requireNonNull(sqs);
  }

  @Override
  public PublishBound publishBound() {
    try {
      var configuration = sqs.serviceClientConfiguration();
      if (configuration == null) return PublishBound.unbounded();
      return configuration
          .overrideConfiguration()
          .apiCallTimeout()
          .map(PublishBound::of)
          .orElseGet(PublishBound::unbounded);
    } catch (UnsupportedOperationException unavailable) {
      return PublishBound.unbounded();
    }
  }

  @Override
  public boolean supports(String destination) {
    return isDestination(destination);
  }

  @Override
  public PublishResult publish(String destination, EventEnvelope event) {
    try {
      return new PublishResult(
          event.id(), destination, sqs.sendMessage(request(destination, event)).messageId());
    } catch (SdkException e) {
      throw new PublishException("Event " + event.id() + " not accepted by " + destination, e);
    }
  }

  static SendMessageRequest request(String destination, EventEnvelope event) {
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
    return request.build();
  }

  /** Whether the address selects SQS, including HTTP emulator queue URLs. */
  public static boolean isDestination(String destination) {
    try {
      URI uri = URI.create(destination);
      String scheme = uri.getScheme();
      String host = uri.getHost();
      if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
          || host == null
          || uri.getUserInfo() != null
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null) return false;
      return host.toLowerCase(Locale.ROOT).contains("sqs")
          || uri.getPath().matches("/[0-9]{12}/[A-Za-z0-9_-]+(?:\\.fifo)?");
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }
}
