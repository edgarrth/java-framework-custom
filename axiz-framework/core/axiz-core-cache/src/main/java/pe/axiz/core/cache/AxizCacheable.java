package pe.axiz.core.cache;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Caches the result of a method (including {@code Mono} and {@code Flux} in reactive services). The
 * key is the cache name, the method (class, name and parameter types, so overloads never share
 * entries) and the arguments encoded by {@link CacheKeys} (type included, no expressions, so it
 * works in native images).
 *
 * <p>The arguments are the whole key: when the result depends on something that is not an argument
 * (the caller, the tenant, the channel), pass it as an argument, otherwise callers share entries.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AxizCacheable {

  /** Cache name, configured under {@code axiz.cache.caches.<name>}. */
  String value();

  /** TTL override in ISO-8601 (e.g. {@code PT5M}); empty uses the cache default. */
  String ttl() default "";

  /**
   * Explicit key namespace, for methods that must read each other's entries (same arguments, same
   * result); empty uses the method signature. Choose it on purpose: two methods with the same
   * namespace and arguments return each other's values.
   */
  String keyName() default "";
}
