package pe.axiz.core.commons;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Catalog of business headers a service accepts, validates and propagates.
 *
 * <p>No header name is hard-coded in the framework: services declare their own (for example a
 * channel or client application header) in configuration.
 */
public final class HeaderCatalog {

  private final Map<String, HeaderDefinition> definitions;

  private HeaderCatalog(Collection<HeaderDefinition> definitions) {
    Map<String, HeaderDefinition> map = new LinkedHashMap<>();
    for (HeaderDefinition d : definitions) {
      if (map.putIfAbsent(d.key(), d) != null) {
        throw new IllegalArgumentException("Duplicate header definition: " + d.name());
      }
    }
    this.definitions = Map.copyOf(map);
  }

  public static HeaderCatalog of(Collection<HeaderDefinition> definitions) {
    return new HeaderCatalog(definitions);
  }

  public static HeaderCatalog empty() {
    return new HeaderCatalog(List.of());
  }

  public Collection<HeaderDefinition> definitions() {
    return definitions.values();
  }

  public Optional<HeaderDefinition> find(String headerName) {
    return Optional.ofNullable(definitions.get(headerName.toLowerCase(Locale.ROOT)));
  }

  /**
   * Validates all declared headers using a lookup function over the inbound message.
   *
   * @param lookup returns the header value or null
   * @return validation errors; empty when valid
   */
  public List<String> validate(Function<String, @Nullable String> lookup) {
    List<String> errors = new ArrayList<>();
    for (HeaderDefinition d : definitions.values()) {
      String error = d.validate(lookup.apply(d.name()));
      if (error != null) {
        errors.add(error);
      }
    }
    return errors;
  }

  /**
   * Extracts the headers that must be propagated (allow-list), skipping absent values.
   *
   * @param lookup returns the header value or null
   * @return header name to value, in declaration order
   */
  public Map<String, String> propagated(Function<String, @Nullable String> lookup) {
    Map<String, String> out = new LinkedHashMap<>();
    for (HeaderDefinition d : definitions.values()) {
      if (d.propagate()) {
        String value = lookup.apply(d.name());
        if (value != null && !value.isBlank()) {
          out.put(d.name(), value);
        }
      }
    }
    return out;
  }
}
