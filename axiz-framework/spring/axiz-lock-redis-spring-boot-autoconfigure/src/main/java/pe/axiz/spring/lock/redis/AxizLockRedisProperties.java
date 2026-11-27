package pe.axiz.spring.lock.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Configuración de redis que conserva sus claves de configuración públicas. */
@ConfigurationProperties("axiz.lock")
public record AxizLockRedisProperties(@DefaultValue("axiz:lock:") String keyPrefix) {
  public AxizLockRedisProperties {
    if (keyPrefix == null || keyPrefix.isBlank())
      throw new IllegalArgumentException("axiz.lock.key-prefix must not be blank");
  }
}
