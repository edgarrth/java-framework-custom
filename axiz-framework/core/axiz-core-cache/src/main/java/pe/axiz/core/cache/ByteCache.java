package pe.axiz.core.cache;

import java.time.Duration;
import java.util.Optional;

/**
 * Nivel de caché a nivel de bytes (memoria local, Redis). Las claves ya incluyen el prefijo de
 * servicio y de caché.
 */
public interface ByteCache {

  Optional<byte[]> get(String key);

  /**
   * Valor y lo que el nivel sabe de su expiración, leídos juntos (un solo viaje de ida y vuelta).
   * Un nivel que no puede saberlo devuelve {@link CachedValue#unknownExpiry}; {@link TwoLevelCache}
   * entonces no guarda copia local, de modo que nunca sirve un valor pasada una expiración que
   * desconoce.
   */
  default Optional<CachedValue> getWithTtl(String key) {
    return get(key).map(CachedValue::unknownExpiry);
  }

  /**
   * Lectura, en nanosegundos, del reloj con el que este nivel expira las entradas. Solo las
   * diferencias entre dos lecturas tienen significado. {@link TwoLevelCache} también mide con él
   * cada lectura de L2, de modo que la copia que guarda aquí se mide en la misma base de tiempo que
   * la expirará. Por defecto es el reloj monotónico de la JVM, el que usa Caffeine.
   */
  default long ticks() {
    return System.nanoTime();
  }

  /** Almacena el valor con un TTL aplicado sobre la misma clave. */
  void put(String key, byte[] value, Duration ttl);

  void evict(String key);

  /**
   * Desaloja todas las claves que empiezan con el prefijo (basado en SCAN, nunca KEYS); devuelve la
   * cantidad.
   */
  long evictByPrefix(String prefix);
}
