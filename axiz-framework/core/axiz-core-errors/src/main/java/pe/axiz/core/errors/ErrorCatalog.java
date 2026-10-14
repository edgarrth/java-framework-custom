package pe.axiz.core.errors;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Externalized error catalog. Starts with the framework definitions and is extended with the
 * service catalog (YAML in the JAR or the {@code <service>-errors} ConfigMap).
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
   * Returns the definition or fails; use it at startup to verify that referenced codes exist.
   *
   * @param code error code
   * @return the definition
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

  /** Builder; later entries replace earlier ones with the same code. */
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
     * Adds entries from flat properties: {@code <CODE>.category}, {@code <CODE>.title} and the
     * optional {@code <CODE>.type} and {@code <CODE>.message-key}.
     *
     * @param properties flat key/value pairs
     * @return this builder
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
