package pe.axiz.spring.security.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Redis key namespace for the rate-limit integration. */
@ConfigurationProperties("axiz.security.rate-limit.redis")
public record AxizSecurityRedisProperties(@DefaultValue("axiz:") String keyPrefix) {
  public AxizSecurityRedisProperties {
    if (keyPrefix == null || keyPrefix.isBlank())
      throw new IllegalArgumentException("Redis rate-limit prefix must not be blank");
  }
}
