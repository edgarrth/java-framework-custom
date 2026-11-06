package pe.axiz.spring.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** API call bound applied only to messaging clients created by Axiz, not application clients. */
@ConfigurationProperties("axiz.messaging.aws")
public record AxizMessagingAwsProperties(@DefaultValue("10s") Duration apiCallTimeout) {
  public AxizMessagingAwsProperties {
    if (!apiCallTimeout.isPositive())
      throw new IllegalArgumentException("messaging AWS api-call-timeout must be positive");
    apiCallTimeout.toNanos();
  }
}
