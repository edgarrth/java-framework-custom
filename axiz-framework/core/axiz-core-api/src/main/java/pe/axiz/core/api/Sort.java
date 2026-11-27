package pe.axiz.core.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import pe.axiz.core.lang.Checks;

/**
 * Especificación de ordenamiento obtenida de {@code field,asc;other,desc} o de {@code field,dir}
 * repetido.
 *
 * @param orders lista ordenada de criterios de ordenamiento
 */
public record Sort(List<Order> orders) {

  private static final Pattern FIELD = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_.]{0,63}$");

  public Sort {
    orders = List.copyOf(orders);
  }

  public static Sort unsorted() {
    return new Sort(List.of());
  }

  public static Sort by(String field, Direction direction) {
    return new Sort(List.of(new Order(field, direction)));
  }

  /**
   * Interpreta expresiones de ordenamiento como {@code "createdAt,desc"}; las direcciones
   * desconocidas toman asc por defecto.
   *
   * @param expressions cada una {@code field[,asc|desc]}
   * @return el ordenamiento interpretado
   */
  public static Sort parse(List<String> expressions) {
    List<Order> orders = new ArrayList<>();
    for (String expression : expressions) {
      for (String part : expression.split(";", -1)) {
        String[] tokens = part.strip().split(",", -1);
        if (tokens.length == 0 || tokens[0].isBlank()) {
          continue;
        }
        Direction dir =
            tokens.length > 1 && "desc".equals(tokens[1].strip().toLowerCase(Locale.ROOT))
                ? Direction.DESC
                : Direction.ASC;
        orders.add(new Order(tokens[0].strip(), dir));
      }
    }
    return new Sort(orders);
  }

  public boolean isSorted() {
    return !orders.isEmpty();
  }

  /** Dirección de ordenamiento. */
  public enum Direction {
    ASC,
    DESC
  }

  /**
   * Un criterio de ordenamiento; los nombres de campo se restringen a identificadores seguros.
   *
   * @param field nombre de la propiedad
   * @param direction dirección
   */
  public record Order(String field, Direction direction) {
    public Order {
      Checks.matches(field, FIELD, "sort field");
      Checks.notNull(direction, "direction");
    }
  }
}
