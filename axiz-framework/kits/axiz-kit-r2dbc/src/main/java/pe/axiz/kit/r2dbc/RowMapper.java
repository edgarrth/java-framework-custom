package pe.axiz.kit.r2dbc;

/**
 * Maps a row to an object.
 *
 * @param <T> mapped type
 */
@FunctionalInterface
public interface RowMapper<T> {

  T map(SqlRow row);
}
