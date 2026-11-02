package pe.axiz.adapter.messaging.sqs;

import pe.axiz.core.messaging.CloudEventJson;
import pe.axiz.core.messaging.EventEnvelope;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;

/** Maps SQS messages (including SNS-delivered raw messages) to envelopes. */
public final class SqsEnvelopes {

  private SqsEnvelopes() {}

  public static EventEnvelope from(Message message) {
    return CloudEventJson.read(message.body());
  }

  /** Delivery attempt from {@code ApproximateReceiveCount} (1 when absent). */
  public static int attempt(Message message) {
    String count = message.attributes().get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT);
    try {
      return count == null ? 1 : Math.max(1, Integer.parseInt(count));
    } catch (NumberFormatException e) {
      return 1;
    }
  }
}
