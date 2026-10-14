package pe.axiz.core.identifiers;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/** Registry of identifier types: built-in packs, ServiceLoader providers and custom types. */
public final class IdentifierRegistry {

  private final Map<String, IdentifierType> types;

  private IdentifierRegistry(Map<String, IdentifierType> types) {
    this.types = Map.copyOf(types);
  }

  /** Registry with every pack discovered through {@link ServiceLoader}. */
  public static IdentifierRegistry withDiscoveredTypes() {
    return builder().discover().build();
  }

  public static Builder builder() {
    return new Builder();
  }

  public Optional<IdentifierType> find(String type) {
    return Optional.ofNullable(types.get(type.toLowerCase(Locale.ROOT)));
  }

  public IdentifierType require(String type) {
    return find(type)
        .orElseThrow(() -> new IllegalArgumentException("Unknown identifier type " + type));
  }

  public boolean isValid(String type, String raw) {
    if (raw == null || raw.isBlank()) {
      return false;
    }
    IdentifierType t = require(type);
    return t.isValid(t.normalize(raw));
  }

  /**
   * Normalizes and validates a raw value.
   *
   * @param type type name
   * @param raw user input
   * @return the identifier
   * @throws InvalidIdentifierException when invalid
   */
  public Identifier parse(String type, String raw) {
    IdentifierType t = require(type);
    String normalized = raw == null ? "" : t.normalize(raw);
    if (normalized.isEmpty() || !t.isValid(normalized)) {
      throw new InvalidIdentifierException(t.name());
    }
    return new Identifier(t.name(), normalized);
  }

  public Collection<String> names() {
    return types.keySet();
  }

  /** Builder. */
  public static final class Builder {
    private final Map<String, IdentifierType> map = new LinkedHashMap<>();

    private Builder() {}

    public Builder register(IdentifierType type) {
      String key = type.name().toLowerCase(Locale.ROOT);
      if (map.putIfAbsent(key, type) != null) {
        throw new IllegalArgumentException("Duplicate identifier type " + key);
      }
      return this;
    }

    public Builder registerAll(List<IdentifierType> list) {
      list.forEach(this::register);
      return this;
    }

    public Builder discover() {
      for (IdentifierTypeProvider p : ServiceLoader.load(IdentifierTypeProvider.class)) {
        registerAll(p.types());
      }
      return this;
    }

    public IdentifierRegistry build() {
      return new IdentifierRegistry(map);
    }
  }
}
