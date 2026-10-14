package pe.axiz.core.audit.sqs;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditPublishException;
import pe.axiz.core.audit.AuditPublisher;
import pe.axiz.core.commons.json.JsonWriter;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * Publishes audit events to an Amazon SQS queue. For FIFO queues the group is the resource and the
 * deduplication id is the event id (deterministic, never random).
 */
public final class SqsAuditPublisher implements AuditPublisher {

  private final SqsClient sqs;
  private final String queueUrl;
  private final boolean fifo;

  public SqsAuditPublisher(SqsClient sqs, String queueUrl) {
    this.sqs = Objects.requireNonNull(sqs);
    this.queueUrl = Objects.requireNonNull(queueUrl);
    this.fifo = queueUrl.endsWith(".fifo");
  }

  @Override
  public void publish(AuditEvent event) {
    SendMessageRequest.Builder request =
        SendMessageRequest.builder()
            .queueUrl(queueUrl)
            .messageBody(cloudEvent(event))
            .messageAttributes(
                Map.of(
                    "ce_id", attribute(event.id()),
                    "ce_type", attribute(AuditEvent.CLOUD_EVENT_TYPE)));
    if (fifo) {
      String group =
          event.resourceType() + ":" + (event.resourceId() == null ? "none" : event.resourceId());
      request.messageGroupId(group).messageDeduplicationId(event.id());
    }
    try {
      sqs.sendMessage(request.build());
    } catch (SdkException e) {
      throw new AuditPublishException("Audit event " + event.id() + " not accepted by SQS", e);
    }
  }

  /** Structured-mode CloudEvent: consumers of the axiz messaging starter read it as any event. */
  static String cloudEvent(AuditEvent event) {
    Map<String, @Nullable Object> m = new LinkedHashMap<>(event.cloudEventAttributes());
    m.put("datacontenttype", "application/json");
    m.put("data", event.toMap());
    return JsonWriter.write(m);
  }

  private static MessageAttributeValue attribute(String value) {
    return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
  }
}
