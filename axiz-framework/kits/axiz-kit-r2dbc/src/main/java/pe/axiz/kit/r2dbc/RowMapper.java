package pe.axiz.kit.r2dbc;

/**
 * Mapea una fila a un objeto.
 *
 * @param <T> tipo mapeado
 */
@FunctionalInterface
public interface RowMapper<T> {

  T map(SqlRow row);
}
