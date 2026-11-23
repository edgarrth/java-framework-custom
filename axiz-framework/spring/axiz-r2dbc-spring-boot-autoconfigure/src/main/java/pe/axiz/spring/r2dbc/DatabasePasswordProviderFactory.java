package pe.axiz.spring.r2dbc;

import pe.axiz.kit.r2dbc.DatabasePasswordProvider;

/**
 * Crea credenciales vinculadas a un destino. Cada resultado pertenece de forma independiente a su
 * pool. Las implementaciones que devuelven recursos AutoCloseable transfieren su propiedad al
 * invocante.
 */
@FunctionalInterface
public interface DatabasePasswordProviderFactory {
  DatabasePasswordProvider create(String r2dbcUrl, String username);
}
