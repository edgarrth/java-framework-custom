package pe.axiz.spring.r2dbc;

import pe.axiz.kit.r2dbc.DatabasePasswordProvider;

/**
 * Creates credentials bound to one destination. Each result is independently owned by its pool.
 * Implementations returning AutoCloseable resources transfer their ownership to the caller.
 */
@FunctionalInterface
public interface DatabasePasswordProviderFactory {
  DatabasePasswordProvider create(String r2dbcUrl, String username);
}
