package pe.axiz.adapter.messaging.sns;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;

/**
 * Publica CloudEvents (JSON estructurado) en tópicos de Amazon SNS (fan-out). El atributo {@code
 * ce_type} habilita las políticas de filtro de suscripción. Los tópicos FIFO usan el subject como
 * grupo y el id del evento como id de deduplicación. Los destinos son ARN de tópicos.
 */
public final class SnsEventPublisher implements EventPublisher, BoundedPublisher {

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "SnsClient is an injected collaborator whose identity and lifecycle must be shared, "
              + "not copied.")
  private final SnsClient sns;

  public SnsEventPublisher(SnsClient sns) {
    this.sns = Objects.requireNonNull(sns);
  }

  @Override
  public PublishBound publishBound() {
    try {
      var configuration = sns.serviceClientConfiguration();
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
    return destination.startsWith("arn:") && destination.contains(":sns:");
  }

  @Override
  public PublishResult publish(String destination, EventEnvelope event) {
    try {
      return new PublishResult(
          event.id(), destination, sns.publish(request(destination, event)).messageId());
    } catch (SdkException e) {
      throw new PublishException("Event " + event.id() + " not accepted by " + destination, e);
    }
  }

  static PublishRequest request(String destination, EventEnvelope event) {
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
    return request.build();
  }
}
