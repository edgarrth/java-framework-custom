package pe.axiz.core.cache;

/**
 * Valor que conoce su propia clave de caché: se implementa en un objeto de valor que no es un
 * record y se usa como argumento de un método en caché. La clave debe identificar el valor por
 * completo (dos valores con la misma clave son intercambiables para la caché) y no debe cambiar
 * entre versiones salvo que la caché pueda vaciarse.
 */
public interface CacheKeyPart {

  /** Identidad estable del valor; {@link CacheKeys} escapa los separadores. */
  String cacheKey();
}
