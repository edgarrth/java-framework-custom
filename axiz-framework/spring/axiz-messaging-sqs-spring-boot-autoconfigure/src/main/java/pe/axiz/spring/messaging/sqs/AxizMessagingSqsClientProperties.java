package pe.axiz.spring.messaging.sqs;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Límite de llamadas a la API aplicado solo a los clientes de mensajería creados por Axiz, no a los
 * clientes de la aplicación.
 */
@ConfigurationProperties("axiz.messaging.aws")
public record AxizMessagingSqsClientProperties(@DefaultValue("10s") Duration apiCallTimeout) {
  public AxizMessagingSqsClientProperties {
    if (apiCallTimeout.toNanos() <= 0)
      throw new IllegalArgumentException("messaging AWS api-call-timeout must be positive");
  }
}
