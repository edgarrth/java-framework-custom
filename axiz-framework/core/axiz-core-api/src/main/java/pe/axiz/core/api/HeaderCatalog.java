package pe.axiz.core.api;

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
 * Catálogo de cabeceras de negocio que un servicio acepta, valida y propaga.
 *
 * <p>Ningún nombre de cabecera está fijo en el framework: los servicios declaran los suyos (por
 * ejemplo, una cabecera de canal o de aplicación cliente) en la configuración.
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
   * Valida todas las cabeceras declaradas mediante una función de búsqueda sobre el mensaje
   * entrante.
   *
   * @param lookup devuelve el valor de la cabecera o null
   * @return errores de validación; vacío cuando es válido
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
   * Extrae las cabeceras que deben propagarse (lista de permitidas), omitiendo los valores
   * ausentes.
   *
   * @param lookup devuelve el valor de la cabecera o null
   * @return nombre de cabecera a valor, en orden de declaración
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
