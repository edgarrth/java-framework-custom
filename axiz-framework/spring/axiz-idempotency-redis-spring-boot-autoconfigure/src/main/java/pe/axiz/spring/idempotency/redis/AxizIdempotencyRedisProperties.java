package pe.axiz.spring.idempotency.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** redis settings retain their public configuration keys. */
@ConfigurationProperties("axiz.idempotency")
public record AxizIdempotencyRedisProperties(@DefaultValue("axiz:idem:") String keyPrefix) {
  public AxizIdempotencyRedisProperties {
    if (keyPrefix == null || keyPrefix.isBlank())
      throw new IllegalArgumentException("axiz.idempotency.key-prefix must not be blank");
  }
}
