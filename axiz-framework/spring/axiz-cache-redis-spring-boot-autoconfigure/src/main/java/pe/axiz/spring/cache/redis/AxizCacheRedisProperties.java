package pe.axiz.spring.cache.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Controls the Redis integration; shared L2 policy belongs to the neutral cache base. */
@ConfigurationProperties("axiz.cache")
public record AxizCacheRedisProperties(@DefaultValue("true") boolean redis) {}
