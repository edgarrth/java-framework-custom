package pe.axiz.kit.r2dbc;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Fila de resultado, independiente del driver. */
public interface SqlRow {

  /** Valor de una columna convertido al tipo, o null. */
  <T> @Nullable T get(String column, Class<T> type);

  /** Valor de una columna no null. */
  default <T> T require(String column, Class<T> type) {
    return Objects.requireNonNull(get(column, type), () -> "column " + column + " is null");
  }
}
