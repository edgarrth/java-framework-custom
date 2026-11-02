package pe.axiz.kit.r2dbc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Named parameters of a statement with their types, so null values can be bound (R2DBC needs the
 * type of a null). Names are referenced in SQL as {@code :name}.
 *
 * <pre>
 * SqlParams.of().bind("id", id).bind("error", error, String.class)
 * </pre>
 */
public final class SqlParams {

  /**
   * One parameter.
   *
   * @param name name without the colon
   * @param value value, or null
   * @param type Java type used to bind a null value
   */
  public record Param(String name, @Nullable Object value, Class<?> type) {
    public Param {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(type, "type");
    }
  }

  private final List<Param> params = new ArrayList<>();

  private SqlParams() {}

  public static SqlParams of() {
    return new SqlParams();
  }

  /** Binds a non-null value; its class is the bind type. */
  public SqlParams bind(String name, Object value) {
    Objects.requireNonNull(value, () -> "value of " + name + " (use bind(name, value, type))");
    return bind(name, value, value.getClass());
  }

  /** Binds a value that may be null. */
  public SqlParams bind(String name, @Nullable Object value, Class<?> type) {
    if (params.stream().anyMatch(p -> p.name().equals(name))) {
      throw new IllegalArgumentException("Parameter " + name + " bound twice");
    }
    params.add(new Param(name, value, type));
    return this;
  }

  public List<Param> params() {
    return Collections.unmodifiableList(params);
  }

  /** The parameter called {@code name}. */
  public Param get(String name) {
    return params.stream()
        .filter(p -> p.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Parameter :" + name + " not bound"));
  }

  @Override
  public String toString() {
    // values are never printed: they may hold personal data
    return "SqlParams" + params.stream().map(Param::name).toList();
  }
}
