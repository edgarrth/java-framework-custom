package pe.axiz.spring.secrets;

import java.util.Objects;
import pe.axiz.core.secrets.AsyncSecretResolver;

/**
 * Fuente nativa ordenada aportada por una integración.
 *
 * @param name nombre de diagnóstico, nunca un valor secreto
 * @param resolver resolvedor nativo
 * @param cacheable indica si aplica la caché TTL común; las instantáneas refrescadas deben usar
 *     false
 */
public record AsyncSecretSource(String name, AsyncSecretResolver resolver, boolean cacheable) {
  public AsyncSecretSource {
    Objects.requireNonNull(name);
    Objects.requireNonNull(resolver);
  }
}
