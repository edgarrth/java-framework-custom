package pe.axiz.spring.secrets;

import java.util.Objects;
import pe.axiz.core.secrets.AsyncSecretResolver;

/**
 * Ordered native source contributed by an integration.
 *
 * @param name diagnostic name, never a secret value
 * @param resolver native resolver
 * @param cacheable whether the common TTL cache applies; refreshed snapshots must use false
 */
public record AsyncSecretSource(String name, AsyncSecretResolver resolver, boolean cacheable) {
  public AsyncSecretSource {
    Objects.requireNonNull(name);
    Objects.requireNonNull(resolver);
  }
}
