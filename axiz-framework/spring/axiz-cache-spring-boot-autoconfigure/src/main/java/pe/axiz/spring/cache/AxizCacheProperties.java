package pe.axiz.spring.cache;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Caché de dos niveles ({@code axiz.cache.*}): L1 en memoria por instancia, L2 mediante un puerto
 * de caché compartido opcional. Las caídas de la caché remota degradan a L1 sin hacer fallar las
 * solicitudes.
 *
 * @param prefix prefijo de las claves; por defecto, el nombre de la aplicación
 * @param defaultTtl TTL de las cachés sin entrada propia
 * @param l1 nivel local
 * @param l2 política del nivel de caché compartido
 * @param caches TTL por nombre de caché
 */
@ConfigurationProperties("axiz.cache")
public record AxizCacheProperties(
    @Nullable String prefix,
    @DefaultValue("10m") Duration defaultTtl,
    @DefaultValue L1 l1,
    @DefaultValue L2 l2,
    @DefaultValue Map<String, CacheSpec> caches) {
  public AxizCacheProperties {
    caches = Collections.unmodifiableMap(new LinkedHashMap<>(caches));
  }

  /** Política de caché compartida; cualquier integración puede aportar el puerto L2. */
  public record L2(@DefaultValue("true") boolean enabled) {}

  /**
   * Nivel local.
   *
   * @param maxEntries entradas conservadas por instancia
   * @param maxTtl las entradas locales nunca viven más que esto, de modo que las réplicas convergen
   *     rápido
   */
  public record L1(@DefaultValue("10000") int maxEntries, @DefaultValue("1m") Duration maxTtl) {}

  /**
   * Una caché.
   *
   * @param ttl tiempo de vida
   */
  public record CacheSpec(@DefaultValue("10m") Duration ttl) {}
}
