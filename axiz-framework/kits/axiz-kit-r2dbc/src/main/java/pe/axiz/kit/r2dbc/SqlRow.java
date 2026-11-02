package pe.axiz.kit.r2dbc;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** A result row, independent of the driver. */
public interface SqlRow {

  /** Value of a column converted to the type, or null. */
  <T> @Nullable T get(String column, Class<T> type);

  /** Value of a non-null column. */
  default <T> T require(String column, Class<T> type) {
    return Objects.requireNonNull(get(column, type), () -> "column " + column + " is null");
  }
}
