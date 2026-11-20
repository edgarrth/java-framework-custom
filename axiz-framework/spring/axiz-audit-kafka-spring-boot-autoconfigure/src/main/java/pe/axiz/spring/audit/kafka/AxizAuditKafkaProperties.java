package pe.axiz.spring.audit.kafka;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** KAFKA audit destination and its transport policy. */
@ConfigurationProperties("axiz.audit.kafka")
public record AxizAuditKafkaProperties(
    @Nullable String topic,
    @DefaultValue("5s") Duration timeout,
    @DefaultValue("2") int sendWorkers,
    @DefaultValue("256") int sendQueueCapacity) {
  public AxizAuditKafkaProperties {
    if (sendWorkers < 1 || sendWorkers > 64 || sendQueueCapacity < 0 || sendQueueCapacity > 65_536)
      throw new IllegalArgumentException(
          "Audit Kafka workers must be 1..64 and queue capacity 0..65536");
    if (topic != null && (topic.isBlank() || topic.contains("${")))
      throw new IllegalArgumentException(
          "axiz.audit.kafka.topic must not be blank or unresolved when configured");
    if (timeout.compareTo(Duration.ofMillis(1)) < 0 || timeout.compareTo(Duration.ofMinutes(1)) > 0)
      throw new IllegalArgumentException("Audit Kafka timeout must be between 1ms and one minute");
  }
}
