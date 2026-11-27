package pe.axiz.spring.messaging.sns;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Límite de llamadas a la API aplicado solo a los clientes de mensajería creados por Axiz, no a los
 * clientes de la aplicación.
 */
@ConfigurationProperties("axiz.messaging.aws")
public record AxizMessagingSnsClientProperties(@DefaultValue("10s") Duration apiCallTimeout) {
  public AxizMessagingSnsClientProperties {
    if (apiCallTimeout.toNanos() <= 0)
      throw new IllegalArgumentException("messaging AWS api-call-timeout must be positive");
  }
}
