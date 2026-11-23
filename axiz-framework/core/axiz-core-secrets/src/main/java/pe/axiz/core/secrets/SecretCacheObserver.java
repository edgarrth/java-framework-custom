package pe.axiz.core.secrets;

import java.time.Duration;

/**
 * Mediciones de una caché acotada: los callbacks no reciben ningún identificador ni valor de
 * secreto. Las implementaciones deben retornar rápido sin bloquear; los fallos no pueden cambiar
 * los resultados de la caché.
 */
public interface SecretCacheObserver {
  SecretCacheObserver NONE = new SecretCacheObserver() {};

  /** Resultado de una carga delegada real, distinto de un fallo de búsqueda en la caché. */
  enum LoadOutcome {
    FOUND,
    ABSENT,
    FAILED
  }

  default void hit() {}

  default void miss() {}

  default void loaded(LoadOutcome outcome, Duration elapsed) {}
}
