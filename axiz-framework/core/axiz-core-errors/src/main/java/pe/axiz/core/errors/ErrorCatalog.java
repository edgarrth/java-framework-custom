package pe.axiz.core.errors;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Catálogo de errores externalizado. Empieza con las definiciones del framework y se extiende con
 * el catálogo del servicio (YAML en el JAR o el ConfigMap {@code <service>-errors}).
 */
public final class ErrorCatalog {

  private final Map<String, ErrorDefinition> definitions;

  private ErrorCatalog(Map<String, ErrorDefinition> definitions) {
    this.definitions = Map.copyOf(definitions);
  }

  public static Builder builder() {
    return new Builder().addAll(CoreErrors.all());
  }

  public static ErrorCatalog defaults() {
    return builder().build();
  }

  public Optional<ErrorDefinition> find(ErrorCode code) {
    return Optional.ofNullable(definitions.get(code.value()));
  }

  public Optional<ErrorDefinition> find(String code) {
    return Optional.ofNullable(definitions.get(code));
  }

  /**
   * Devuelve la definición o falla; se usa al arrancar para verificar que existan los códigos
   * referenciados.
   *
   * @param code código de error
   * @return la definición
   */
  public ErrorDefinition require(String code) {
    return find(code).orElseThrow(() -> new IllegalArgumentException("Unknown error code " + code));
  }

  public Collection<ErrorDefinition> definitions() {
    return definitions.values();
  }

  public int size() {
    return definitions.size();
  }

  /** Builder; las entradas posteriores reemplazan a las previas con el mismo código. */
  public static final class Builder {
    private final Map<String, ErrorDefinition> map = new LinkedHashMap<>();

    private Builder() {}

    public Builder add(ErrorDefinition definition) {
      map.put(definition.code().value(), definition);
      return this;
    }

    public Builder addAll(Collection<ErrorDefinition> definitions) {
      definitions.forEach(this::add);
      return this;
    }

    /**
     * Agrega entradas a partir de propiedades planas: {@code <CODE>.category}, {@code <CODE>.title}
     * y los opcionales {@code <CODE>.type} y {@code <CODE>.message-key}.
     *
     * @param properties pares clave/valor planos
     * @return este builder
     */
    public Builder addProperties(Map<String, String> properties) {
      TreeSet<String> codes = new TreeSet<>();
      for (String key : properties.keySet()) {
        int dot = key.lastIndexOf('.');
        if (dot > 0) {
          codes.add(key.substring(0, dot));
        }
      }
      for (String code : codes) {
        String category = properties.get(code + ".category");
        if (category == null) {
          throw new IllegalArgumentException("Error " + code + " has no category");
        }
        ErrorCategory cat = ErrorCategory.valueOf(normalize(category));
        String type = properties.get(code + ".type");
        ErrorType errorType = type == null ? cat.defaultType() : ErrorType.valueOf(normalize(type));
        String title = properties.getOrDefault(code + ".title", cat.name());
        String messageKey = properties.getOrDefault(code + ".message-key", "error." + code);
        add(new ErrorDefinition(ErrorCode.of(code), cat, errorType, title, messageKey));
      }
      return this;
    }

    private static String normalize(String value) {
      return value.strip().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    public ErrorCatalog build() {
      return new ErrorCatalog(map);
    }
  }
}
