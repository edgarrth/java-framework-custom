package pe.axiz.spring.security.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Espacio de nombres de claves Redis para la integración de límites de tasa. */
@ConfigurationProperties("axiz.security.rate-limit.redis")
public record AxizSecurityRedisProperties(@DefaultValue("axiz:") String keyPrefix) {
  public AxizSecurityRedisProperties {
    if (keyPrefix == null || keyPrefix.isBlank())
      throw new IllegalArgumentException("Redis rate-limit prefix must not be blank");
  }
}
