package pe.axiz.adapter.messaging.sqs;

import pe.axiz.core.messaging.CloudEventJson;
import pe.axiz.core.messaging.EventEnvelope;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;

/** Convierte mensajes SQS (incluidos los mensajes raw entregados por SNS) en envelopes. */
public final class SqsEnvelopes {

  private SqsEnvelopes() {}

  public static EventEnvelope from(Message message) {
    return CloudEventJson.read(message.body());
  }

  /** Intento de entrega a partir de {@code ApproximateReceiveCount} (1 cuando falta). */
  public static int attempt(Message message) {
    String count = message.attributes().get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT);
    try {
      return count == null ? 1 : Math.max(1, Integer.parseInt(count));
    } catch (NumberFormatException e) {
      return 1;
    }
  }
}
