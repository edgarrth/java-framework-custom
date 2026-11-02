package pe.axiz.kit.r2dbc;

import io.r2dbc.spi.Row;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** {@link SqlRow} over an R2DBC row. */
public final class R2dbcRow implements SqlRow {

  private final Row row;

  public R2dbcRow(Row row) {
    this.row = Objects.requireNonNull(row);
  }

  @Override
  public <T> @Nullable T get(String column, Class<T> type) {
    return row.get(column, type);
  }
}
