package pe.axiz.core.commons;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Sort specification parsed from {@code field,asc;other,desc} or repeated {@code field,dir}.
 *
 * @param orders ordered list of sort orders
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
   * Parses sort expressions such as {@code "createdAt,desc"}; unknown directions default to asc.
   *
   * @param expressions each one {@code field[,asc|desc]}
   * @return the parsed sort
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

  /** Sort direction. */
  public enum Direction {
    ASC,
    DESC
  }

  /**
   * One sort criterion; field names are restricted to safe identifiers.
   *
   * @param field property name
   * @param direction direction
   */
  public record Order(String field, Direction direction) {
    public Order {
      Checks.matches(field, FIELD, "sort field");
      Checks.notNull(direction, "direction");
    }
  }
}
