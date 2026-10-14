package pe.axiz.core.cache;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Caches the result of a method (including {@code Mono} and {@code Flux} in reactive services). The
 * key is built from the arguments by the configured key generator (no expressions, so it works in
 * native images).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AxizCacheable {

  /** Cache name, configured under {@code axiz.cache.caches.<name>}. */
  String value();

  /** TTL override in ISO-8601 (e.g. {@code PT5M}); empty uses the cache default. */
  String ttl() default "";
}
