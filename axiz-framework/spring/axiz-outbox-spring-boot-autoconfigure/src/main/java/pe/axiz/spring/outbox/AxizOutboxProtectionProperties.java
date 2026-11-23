package pe.axiz.spring.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import pe.axiz.core.crypto.PayloadProtectionMode;

/**
 * Política de payload y presupuesto de descifrado reservado antes de cada publicación del relay.
 */
@ConfigurationProperties("axiz.outbox")
public record AxizOutboxProtectionProperties(
    @DefaultValue("NONE") PayloadProtectionMode payloadProtection,
    @DefaultValue("5s") Duration payloadReadTimeout) {
  public AxizOutboxProtectionProperties {
    if (payloadReadTimeout.compareTo(Duration.ofMillis(1)) < 0
        || payloadReadTimeout.compareTo(Duration.ofMinutes(1)) > 0)
      throw new IllegalArgumentException("Outbox payload read timeout must be between 1ms and 1m");
  }

  public Duration readBudget() {
    return payloadProtection == PayloadProtectionMode.NONE ? Duration.ZERO : payloadReadTimeout;
  }
}
