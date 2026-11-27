package pe.axiz.adapter.audit.sqs;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditEventJson;
import pe.axiz.core.audit.AuditPublishException;
import pe.axiz.core.audit.AuditPublisher;
import pe.axiz.core.json.JsonWriter;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

/**
 * Publica eventos de auditoría en una cola de Amazon SQS. En colas FIFO el grupo es el recurso y el
 * id de deduplicación identifica el intento sellado (estable para la retransmisión).
 */
public final class SqsAuditPublisher implements AuditPublisher {

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "SqsClient is an injected collaborator whose identity and lifecycle must be shared, "
              + "not copied.")
  private final SqsClient sqs;

  private final String queueUrl;
  private final boolean fifo;

  public SqsAuditPublisher(SqsClient sqs, String queueUrl) {
    this.sqs = Objects.requireNonNull(sqs);
    this.queueUrl = Objects.requireNonNull(queueUrl);
    if (queueUrl.isBlank())
      throw new IllegalArgumentException("Audit SQS queue URL must not be blank");
    this.fifo = queueUrl.endsWith(".fifo");
  }

  @Override
  public void publish(AuditEvent event) {
    try {
      requireAcknowledgement(sqs.sendMessage(request(queueUrl, fifo, event)));
    } catch (SdkException e) {
      throw new AuditPublishException("Audit event " + event.id() + " not accepted by SQS", e);
    }
  }

  static void requireAcknowledgement(@Nullable SendMessageResponse response) {
    if (response == null || response.messageId() == null || response.messageId().isBlank())
      throw new AuditPublishException(
          "SQS did not return an acceptance identifier",
          new IllegalStateException("Missing durable acknowledgement"));
  }

  static SendMessageRequest request(String queueUrl, boolean fifo, AuditEvent event) {
    SendMessageRequest.Builder request =
        SendMessageRequest.builder()
            .queueUrl(queueUrl)
            .messageBody(cloudEvent(event))
            .messageAttributes(
                Map.of(
                    "ce_id", attribute(event.deliveryId()),
                    "ce_type", attribute(AuditEvent.CLOUD_EVENT_TYPE)));
    if (fifo) {
      String group =
          event.resourceType() + ":" + (event.resourceId() == null ? "none" : event.resourceId());
      request.messageGroupId(group).messageDeduplicationId(event.deliveryId());
    }
    return request.build();
  }

  /**
   * CloudEvent en modo estructurado: los consumidores del starter de mensajería de axiz lo leen
   * como cualquier evento.
   */
  static String cloudEvent(AuditEvent event) {
    Map<String, @Nullable Object> m = new LinkedHashMap<>(event.cloudEventAttributes());
    m.put("datacontenttype", "application/json");
    m.put(
        "data_base64",
        Base64.getEncoder()
            .encodeToString(AuditEventJson.write(event).getBytes(StandardCharsets.UTF_8)));
    return JsonWriter.writeCanonical(m);
  }

  private static MessageAttributeValue attribute(String value) {
    return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
  }
}
