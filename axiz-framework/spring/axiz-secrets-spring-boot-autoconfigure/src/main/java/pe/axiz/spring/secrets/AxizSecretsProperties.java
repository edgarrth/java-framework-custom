package pe.axiz.spring.secrets;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Política de caché compartida; la selección de fuentes corresponde a las integraciones. */
@ConfigurationProperties("axiz.secrets")
public record AxizSecretsProperties(@DefaultValue("5m") Duration cacheTtl) {
  public AxizSecretsProperties {
    if (!cacheTtl.isPositive())
      throw new IllegalArgumentException("axiz.secrets.cache-ttl must be positive");
  }
}
