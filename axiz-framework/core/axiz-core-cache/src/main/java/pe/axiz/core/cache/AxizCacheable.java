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
 * <p>Entries are kept apart by tenant by default ({@link #scope()}): a call bound to one tenant
 * never reads an entry written for another, also when the method reads the tenant from the context
 * and takes only tenant-local ids. When the result also depends on who asks, use {@link
 * CacheScope#CALLER}; for data that is the same for everyone, {@link CacheScope#GLOBAL}. Anything
 * else the result depends on (the channel, a flag) must be an argument, otherwise callers share
 * entries. A cache hit does not run the method: authorization checks belong before the call.
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

  /** Who shares entries, on top of the arguments: the tenant by default. */
  CacheScope scope() default CacheScope.TENANT;
}
