package pe.axiz.core.cache;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fachada de caché tipada sobre un {@link ByteCache}: antepone un prefijo a las claves ({@code
 * <service>:<cache>:<key>}), serializa con un {@link ValueCodec} y carga los valores faltantes una
 * sola vez por clave a la vez (single flight) para evitar estampidas. Los resultados null no se
 * guardan en caché; las colecciones vacías y de un solo elemento se guardan como cualquier otro
 * valor.
 */
public final class AxizCache {

  private static final Logger LOG = LoggerFactory.getLogger(AxizCache.class);

  private final String prefix;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "ByteCache is an injected collaborator whose identity and lifecycle must be shared, "
              + "not copied.")
  private final ByteCache cache;

  private final ValueCodec codec;
  private final Duration defaultTtl;
  private final Map<String, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();

  public AxizCache(
      String servicePrefix,
      String cacheName,
      ByteCache cache,
      ValueCodec codec,
      Duration defaultTtl) {
    if (!cacheName.matches("^[a-z0-9][a-z0-9.-]{0,62}$")) {
      throw new IllegalArgumentException("Invalid cache name " + cacheName);
    }
    this.prefix = servicePrefix + ":" + cacheName + ":";
    this.cache = Objects.requireNonNull(cache);
    this.codec = Objects.requireNonNull(codec);
    this.defaultTtl = Objects.requireNonNull(defaultTtl);
  }

  public String fullKey(String key) {
    return prefix + key;
  }

  public <T> Optional<T> get(String key, Class<T> type) {
    Optional<byte[]> bytes = cache.get(fullKey(key));
    if (bytes.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(codec.decode(bytes.get(), type));
    } catch (RuntimeException e) {
      LOG.warn("Discarding undecodable cache entry: {}", e.getClass().getSimpleName());
      cache.evict(fullKey(key));
      return Optional.empty();
    }
  }

  public void put(String key, Object value) {
    put(key, value, defaultTtl);
  }

  public void put(String key, Object value, Duration ttl) {
    cache.put(fullKey(key), codec.encode(value), ttl);
  }

  public void evict(String key) {
    cache.evict(fullKey(key));
  }

  /** Desaloja todas las entradas de esta caché. */
  public long clear() {
    return cache.evictByPrefix(prefix);
  }

  /**
   * Devuelve el valor en caché, o lo carga, lo guarda en caché y lo devuelve.
   *
   * @param key clave dentro de esta caché
   * @param type tipo del valor
   * @param loader carga el valor desde la fuente de verdad (puede devolver null: no se guarda en
   *     caché)
   * @return el valor o null
   */
  @SuppressWarnings("unchecked")
  public <T> @Nullable T getOrLoad(String key, Class<T> type, Supplier<@Nullable T> loader) {
    Optional<T> cached = get(key, type);
    if (cached.isPresent()) {
      return cached.get();
    }
    CompletableFuture<Object> mine = new CompletableFuture<>();
    CompletableFuture<Object> existing = inFlight.putIfAbsent(fullKey(key), mine);
    if (existing != null) {
      try {
        return (T) existing.get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return loader.get();
      } catch (ExecutionException e) {
        return loader.get();
      }
    }
    try {
      // Un fallo de caché puede haber empezado antes de que el propietario previo publicara su
      // valor y retirara su vuelo. Se vuelve a verificar tras convertirse en propietario sin
      // extender el TTL de una entrada existente.
      Optional<T> published = get(key, type);
      if (published.isPresent()) {
        T value = published.get();
        mine.complete(value);
        return value;
      }
      T value = loader.get();
      if (value != null) {
        put(key, value);
      }
      mine.complete(value);
      return value;
    } catch (RuntimeException e) {
      mine.completeExceptionally(e);
      throw e;
    } finally {
      inFlight.remove(fullKey(key), mine);
    }
  }
}
