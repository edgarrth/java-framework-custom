package pe.axiz.spring.cache.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Controla la integración con Redis; la política L2 compartida pertenece a la base neutral de
 * caché.
 */
@ConfigurationProperties("axiz.cache")
public record AxizCacheRedisProperties(@DefaultValue("true") boolean redis) {}
