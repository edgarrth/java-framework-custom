package pe.axiz.spring.messaging.kafka;

import java.util.Map;

/**
 * Integración de autenticación aplicada antes de crear cualquier productor, consumidor o admin de
 * Kafka.
 */
public interface KafkaClientAuthentication {
  String name();

  void configure(Map<String, Object> properties);
}
