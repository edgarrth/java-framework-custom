package pe.axiz.core.cache;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Guarda en caché el resultado de un método (incluidos {@code Mono} y {@code Flux} en servicios
 * reactivos). La clave es el nombre de la caché, el método (clase, nombre y tipos de parámetros, de
 * modo que las sobrecargas nunca comparten entradas) y los argumentos codificados por {@link
 * CacheKeys} (con el tipo incluido y sin expresiones, por lo que funciona en imágenes nativas).
 *
 * <p>Por defecto las entradas se separan por tenant ({@link #scope()}): una llamada ligada a un
 * tenant nunca lee una entrada escrita para otro, incluso cuando el método lee el tenant del
 * contexto y recibe solo ids locales al tenant. Cuando el resultado también depende de quién
 * pregunta, se usa {@link CacheScope#CALLER}; para datos iguales para todos, {@link
 * CacheScope#GLOBAL}. Cualquier otra cosa de la que dependa el resultado (el canal, un flag) debe
 * ser un argumento; de lo contrario, los llamadores comparten entradas. Un acierto de caché no
 * ejecuta el método: las verificaciones de autorización van antes de la llamada.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AxizCacheable {

  /** Nombre de la caché, configurada bajo {@code axiz.cache.caches.<name>}. */
  String value();

  /**
   * TTL específico en ISO-8601 (por ejemplo {@code PT5M}); vacío usa el valor por defecto de la
   * caché.
   */
  String ttl() default "";

  /**
   * Namespace de clave explícito, para métodos que deben leer las entradas del otro (mismos
   * argumentos, mismo resultado); vacío usa la firma del método. Se elige a propósito: dos métodos
   * con el mismo namespace y argumentos devuelven los valores del otro.
   */
  String keyName() default "";

  /** Quién comparte las entradas, además de los argumentos: por defecto, el tenant. */
  CacheScope scope() default CacheScope.TENANT;
}
