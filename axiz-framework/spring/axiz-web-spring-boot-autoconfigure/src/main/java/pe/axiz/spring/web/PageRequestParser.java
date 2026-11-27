package pe.axiz.spring.web;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.api.PageRequest;
import pe.axiz.core.api.Sort;
import pe.axiz.core.errors.FieldViolation;
import pe.axiz.core.errors.ValidationException;

/**
 * Interpreta los parámetros de query {@code page}, {@code size} y {@code sort}. Las páginas
 * empiezan en cero; los valores inválidos se rechazan con un problema 400 en lugar de corregirse en
 * silencio.
 */
public final class PageRequestParser {

  private static final Pattern FIELD = Pattern.compile("^[A-Za-z][A-Za-z0-9_.]{0,63}$");

  private final int defaultSize;
  private final int maxSize;

  public PageRequestParser(int defaultSize, int maxSize) {
    this.maxSize = Math.min(maxSize, PageRequest.MAX_SIZE);
    this.defaultSize = Math.min(defaultSize, this.maxSize);
  }

  public PageRequest parse(
      @Nullable String page,
      @Nullable String size,
      @Nullable List<String> sort,
      @Nullable Paging rules) {
    List<FieldViolation> violations = new ArrayList<>();
    int p = number(page, 0, "page", violations);
    int fallbackSize =
        rules != null && rules.defaultSize() > 0
            ? Math.min(rules.defaultSize(), maxSize)
            : defaultSize;
    int s = number(size, fallbackSize, "size", violations);
    if (p < 0) {
      violations.add(FieldViolation.of("page", "Min", "page must be zero or greater"));
    }
    if (s < 1 || s > maxSize) {
      violations.add(FieldViolation.of("size", "Range", "size must be between 1 and " + maxSize));
    }
    List<String> expressions =
        sort == null || sort.isEmpty()
            ? (rules != null ? Arrays.asList(rules.defaultSort()) : List.of())
            : sort;
    Sort parsed;
    try {
      for (String expression : expressions) {
        for (String criterion : expression.split(";", -1)) {
          String[] parts = criterion.strip().split(",", -1);
          if (parts.length > 2
              || (parts.length == 2
                  && !"asc".equalsIgnoreCase(parts[1].strip())
                  && !"desc".equalsIgnoreCase(parts[1].strip()))) {
            throw new IllegalArgumentException("invalid sort direction");
          }
        }
      }
      parsed = Sort.parse(expressions);
    } catch (IllegalArgumentException malformed) {
      // el core sigue rechazando las expresiones malformadas; en el límite HTTP son un error del
      // cliente (400 con el campo), nunca un 500. El valor no se devuelve en la respuesta
      violations.add(
          FieldViolation.of(
              "sort", "Pattern", "sort must be field[,asc|desc] (letters, digits, _ .)"));
      throw new ValidationException(violations);
    }
    Set<String> allowed = rules != null ? Set.of(rules.sortable()) : Set.of();
    for (Sort.Order o : parsed.orders()) {
      boolean ok =
          allowed.isEmpty() ? FIELD.matcher(o.field()).matches() : allowed.contains(o.field());
      if (!ok) {
        violations.add(
            FieldViolation.of("sort", "Sortable", "sorting by this field is not allowed"));
        break;
      }
    }
    if (!violations.isEmpty()) {
      throw new ValidationException(violations);
    }
    return new PageRequest(p, s, parsed);
  }

  private static int number(
      @Nullable String value, int fallback, String field, List<FieldViolation> violations) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return Integer.parseInt(value.strip());
    } catch (NumberFormatException e) {
      violations.add(FieldViolation.of(field, "Integer", field + " must be an integer"));
      return fallback;
    }
  }
}
