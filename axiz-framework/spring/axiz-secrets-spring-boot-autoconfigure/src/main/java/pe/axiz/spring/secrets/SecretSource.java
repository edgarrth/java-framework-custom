package pe.axiz.spring.secrets;

import pe.axiz.core.secrets.SecretResolver;

/**
 * A secret source contributed by an adapter; sources are queried in bean order after mounted files
 * and the environment.
 *
 * @param name source name for diagnostics
 * @param resolver resolver
 */
public record SecretSource(String name, SecretResolver resolver) {}
