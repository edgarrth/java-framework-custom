package pe.axiz.kit.r2dbc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Parámetros con nombre de una sentencia con sus tipos, para poder enlazar valores null (R2DBC
 * necesita el tipo de un null). Los nombres se referencian en el SQL como {@code :name}.
 *
 * <pre>
 * SqlParams.of().bind("id", id).bind("error", error, String.class)
 * </pre>
 */
public final class SqlParams {

  /**
   * Un parámetro.
   *
   * @param name nombre sin los dos puntos
   * @param value valor, o null
   * @param type tipo Java usado para enlazar un valor null
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

  /** Enlaza un valor no null; su clase es el tipo de enlace. */
  public SqlParams bind(String name, Object value) {
    Objects.requireNonNull(value, () -> "value of " + name + " (use bind(name, value, type))");
    return bind(name, value, value.getClass());
  }

  /** Enlaza un valor que puede ser null. */
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

  /** Parámetro llamado {@code name}. */
  public Param get(String name) {
    return params.stream()
        .filter(p -> p.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Parameter :" + name + " not bound"));
  }

  @Override
  public String toString() {
    // los valores nunca se imprimen: pueden contener datos personales
    return "SqlParams" + params.stream().map(Param::name).toList();
  }
}
