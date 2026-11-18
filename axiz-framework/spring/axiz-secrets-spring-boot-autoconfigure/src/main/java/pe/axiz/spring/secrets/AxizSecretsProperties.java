package pe.axiz.spring.secrets;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Shared cache policy; source selection belongs to integrations. */
@ConfigurationProperties("axiz.secrets")
public record AxizSecretsProperties(@DefaultValue("5m") Duration cacheTtl) {
  public AxizSecretsProperties {
    if (!cacheTtl.isPositive())
      throw new IllegalArgumentException("axiz.secrets.cache-ttl must be positive");
  }
}
