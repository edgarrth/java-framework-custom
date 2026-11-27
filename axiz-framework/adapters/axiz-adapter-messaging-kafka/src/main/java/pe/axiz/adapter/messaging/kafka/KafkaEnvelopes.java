package pe.axiz.adapter.messaging.kafka;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import pe.axiz.core.messaging.CloudEventHeaders;
import pe.axiz.core.messaging.EventEnvelope;

/** Convierte los registros Kafka consumidos de vuelta en envelopes. */
public final class KafkaEnvelopes {

  private KafkaEnvelopes() {}

  public static EventEnvelope from(ConsumerRecord<String, byte[]> record) {
    List<String> names = new ArrayList<>();
    for (Header h : record.headers()) {
      names.add(h.key());
    }
    byte[] body = record.value() == null ? new byte[0] : record.value();
    return CloudEventHeaders.fromHeaders(
        name -> {
          Header h = record.headers().lastHeader(name);
          return h == null || h.value() == null
              ? null
              : new String(h.value(), StandardCharsets.UTF_8);
        },
        names,
        body);
  }
}
