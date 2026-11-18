package pe.axiz.spring.messaging.kafka;

import java.util.Map;

/** Authentication integration applied before any Kafka producer, consumer or admin is created. */
public interface KafkaClientAuthentication {
  String name();

  void configure(Map<String, Object> properties);
}
