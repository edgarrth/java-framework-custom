package pe.axiz.spring.secrets;

import pe.axiz.core.secrets.AsyncSecretResolver;

/**
 * Native source contributed by an adapter, ordered after mounted snapshots and environment.
 *
 * @param name source name for diagnostics
 * @param resolver native resolver
 */
public record AsyncSecretSource(String name, AsyncSecretResolver resolver) {}
