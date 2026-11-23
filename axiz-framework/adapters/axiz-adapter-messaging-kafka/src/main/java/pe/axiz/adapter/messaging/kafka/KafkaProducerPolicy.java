package pe.axiz.adapter.messaging.kafka;

import java.util.Map;

/** Configuración de publicación Kafka confiable, independiente del proveedor de autenticación. */
public final class KafkaProducerPolicy {
  private KafkaProducerPolicy() {}

  public static Map<String, Object> reliableProducer() {
    return Map.of(
        "acks",
        "all",
        "enable.idempotence",
        "true",
        "max.in.flight.requests.per.connection",
        "5",
        "delivery.timeout.ms",
        "120000");
  }
}
