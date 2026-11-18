package pe.axiz.spring.audit.kafka;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** KAFKA audit destination and its transport policy. */
@ConfigurationProperties("axiz.audit.kafka")
public record AxizAuditKafkaProperties(
    @Nullable String topic, @DefaultValue("5s") Duration timeout) {
  public AxizAuditKafkaProperties {
    if (topic != null && (topic.isBlank() || topic.contains("${")))
      throw new IllegalArgumentException(
          "axiz.audit.kafka.topic must not be blank or unresolved when configured");
    if (timeout.compareTo(Duration.ofMillis(1)) < 0 || timeout.compareTo(Duration.ofMinutes(1)) > 0)
      throw new IllegalArgumentException("Audit Kafka timeout must be between 1ms and one minute");
  }
}
