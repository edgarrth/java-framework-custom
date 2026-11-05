package pe.axiz.spring.outbox;

import java.time.Duration;
import org.springframework.core.env.Environment;

/** Startup safety independent of metrics or the presence of a relay on this replica. */
final class OutboxLeaseValidation {
  private OutboxLeaseValidation() {}

  static void validate(AxizOutboxProperties properties, Environment environment) {
    boolean kafka =
        environment.containsProperty("axiz.messaging.kafka.bootstrap-servers")
            || environment.containsProperty("spring.kafka.bootstrap-servers")
            || environment.containsProperty("spring.kafka.bootstrap-servers[0]");
    if (kafka && properties.relay().leaseDuration().compareTo(Duration.ofMillis(120_000)) <= 0)
      throw new IllegalArgumentException(
          "axiz.outbox.relay.lease-duration must exceed Kafka " + "delivery.timeout.ms (120000)");
  }
}
