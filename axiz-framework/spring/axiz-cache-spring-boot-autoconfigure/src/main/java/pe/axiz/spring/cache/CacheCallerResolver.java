package pe.axiz.spring.cache;

import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;

/**
 * Who the caller of a {@link pe.axiz.core.cache.CacheScope#CALLER} cache is. The identity must be
 * unique across every way callers authenticate to the service: a JWT subject is unique only within
 * its issuer, so two issuers may hand out the same {@code sub} to different people.
 *
 * <p>The default ({@link DefaultCacheCallerResolver}) knows the authentications of the axiz
 * security starter and of the Spring resource server. A service with another kind of authentication
 * declares a bean of this type; an authentication it cannot tell apart safely returns {@code null}
 * and the call runs without the cache.
 */
@FunctionalInterface
public interface CacheCallerResolver {

  /**
   * The identity of an authenticated caller.
   *
   * @param authentication the current, authenticated, non-anonymous authentication
   * @return a value that differs for every distinct caller, or {@code null} to skip the cache
   */
  @Nullable String identity(Authentication authentication);
}
