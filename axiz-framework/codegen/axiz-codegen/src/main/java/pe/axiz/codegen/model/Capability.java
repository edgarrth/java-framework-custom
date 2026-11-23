package pe.axiz.codegen.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Una capacidad que el initializr y {@code axiz add} pueden agregar a un servicio.
 *
 * @param name id de la capacidad, p. ej., {@code idempotency-redis}
 * @param starter artefacto starter de axiz
 * @param description lo que aporta
 * @param dependencies artefactos adicionales como {@code groupId:artifactId}
 * @param requires capacidades que se agregan junto con esta
 * @param requiresAnyOf se requiere al menos una de estas capacidades seleccionadas de forma
 *     explícita
 * @param properties entradas de {@code application.yaml} (claves planas)
 * @param localProperties entradas de {@code application-local.yaml} (claves planas)
 * @param configMapKeys claves esperadas en el ConfigMap del servicio
 * @param secrets claves del secreto del servicio
 * @param example ejemplo de uso que se muestra tras agregarla
 * @param persistence por almacén ({@code aurora} sobre JDBC, {@code r2dbc} para Aurora desde el
 *     stack reactivo, {@code dynamodb}; ver {@link Catalog#store}) las propiedades, dependencias y
 *     capacidades requeridas adicionales del almacén; vacío cuando la capacidad no necesita
 *     almacén, y un almacén ausente de un mapa no vacío no está soportado
 */
public record Capability(
    String name,
    String starter,
    String description,
    List<String> dependencies,
    List<String> requires,
    List<String> requiresAnyOf,
    Map<String, Object> properties,
    Map<String, Object> localProperties,
    List<String> configMapKeys,
    List<String> secrets,
    String example,
    Map<String, Store> persistence,
    List<Stack> stacks) {

  /**
   * Adiciones específicas del almacén.
   *
   * @param properties entradas adicionales de {@code application.yaml}
   * @param dependencies artefactos adicionales ({@code groupId:artifactId})
   * @param requires capacidades que se agregan junto con esta
   */
  public record Store(
      Map<String, Object> properties, List<String> dependencies, List<String> requires) {
    public Store {
      properties = Catalog.ordered(properties);
      dependencies = List.copyOf(dependencies);
      requires = List.copyOf(requires);
    }

    @Override
    public Map<String, Object> properties() {
      return Snapshots.properties(properties);
    }

    public Store(Map<String, Object> properties, List<String> dependencies) {
      this(properties, dependencies, List.of());
    }
  }

  public Capability {
    stacks = List.copyOf(stacks);
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(starter, "starter");
    dependencies = List.copyOf(dependencies);
    requires = List.copyOf(requires);
    requiresAnyOf = List.copyOf(requiresAnyOf);
    properties = Catalog.ordered(properties);
    localProperties = Catalog.ordered(localProperties);
    configMapKeys = List.copyOf(configMapKeys);
    secrets = List.copyOf(secrets);
    persistence = Collections.unmodifiableMap(new LinkedHashMap<>(persistence));
  }

  public boolean supports(Stack stack) {
    return stacks.contains(stack);
  }

  /** Indica si la capacidad funciona con el almacén indicado. */
  public boolean supports(String store) {
    return persistence.isEmpty() || persistence.containsKey(store);
  }

  /** Almacenes que soporta la capacidad, en el orden canónico de {@link Catalog#STORES}. */
  public List<String> stores() {
    return Catalog.STORES.stream().filter(persistence::containsKey).toList();
  }

  /** Capacidades requeridas, incluidas las específicas del almacén. */
  public List<String> requires(String store) {
    List<String> out = new ArrayList<>(requires);
    Store s = persistence.get(store);
    if (s != null) {
      out.addAll(s.requires());
    }
    return out;
  }

  /** Propiedades, incluidas las específicas del almacén. */
  public Map<String, Object> properties(String persistenceOption) {
    Map<String, Object> out = new LinkedHashMap<>(properties);
    Store store = persistence.get(persistenceOption);
    if (store != null) {
      out.putAll(store.properties());
    }
    return out;
  }

  /** Dependencias, incluidas las específicas del almacén. */
  public List<String> dependencies(String persistenceOption) {
    List<String> out = new ArrayList<>(dependencies);
    Store store = persistence.get(persistenceOption);
    if (store != null) {
      out.addAll(store.dependencies());
    }
    return out;
  }

  @Override
  public Map<String, Object> properties() {
    return Snapshots.properties(properties);
  }

  @Override
  public Map<String, Object> localProperties() {
    return Snapshots.properties(localProperties);
  }

  /** Construcción con nombre; build verifica los valores obligatorios y las invariantes. */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Estado mutable de construcción; se usa un builder por operación, nunca como estado compartido.
   */
  public static final class Builder {
    private @Nullable String name = null;
    private @Nullable String starter = null;
    private @Nullable String description = null;
    private @Nullable List<String> dependencies = List.of();
    private @Nullable List<String> requires = List.of();
    private @Nullable List<String> requiresAnyOf = List.of();
    private @Nullable Map<String, Object> properties = Map.of();
    private @Nullable Map<String, Object> localProperties = Map.of();
    private @Nullable List<String> configMapKeys = List.of();
    private @Nullable List<String> secrets = List.of();
    private @Nullable String example = null;
    private @Nullable Map<String, Store> persistence = Map.of();
    private @Nullable List<Stack> stacks = List.of();

    private Builder() {}

    public Builder name(String value) {
      this.name = value;
      return this;
    }

    public Builder starter(String value) {
      this.starter = value;
      return this;
    }

    public Builder description(String value) {
      this.description = value;
      return this;
    }

    public Builder dependencies(List<String> value) {
      this.dependencies = List.copyOf(value);
      return this;
    }

    public Builder requires(List<String> value) {
      this.requires = List.copyOf(value);
      return this;
    }

    public Builder requiresAnyOf(List<String> value) {
      this.requiresAnyOf = List.copyOf(value);
      return this;
    }

    public Builder properties(Map<String, Object> value) {
      this.properties = Map.copyOf(value);
      return this;
    }

    public Builder localProperties(Map<String, Object> value) {
      this.localProperties = Map.copyOf(value);
      return this;
    }

    public Builder configMapKeys(List<String> value) {
      this.configMapKeys = List.copyOf(value);
      return this;
    }

    public Builder secrets(List<String> value) {
      this.secrets = List.copyOf(value);
      return this;
    }

    public Builder example(String value) {
      this.example = value;
      return this;
    }

    public Builder persistence(Map<String, Store> value) {
      this.persistence = Map.copyOf(value);
      return this;
    }

    public Builder stacks(List<Stack> value) {
      this.stacks = List.copyOf(value);
      return this;
    }

    public Capability build() {
      return new Capability(
          Objects.requireNonNull(name, "name"),
          Objects.requireNonNull(starter, "starter"),
          Objects.requireNonNull(description, "description"),
          Objects.requireNonNull(dependencies, "dependencies"),
          Objects.requireNonNull(requires, "requires"),
          Objects.requireNonNull(requiresAnyOf, "requiresAnyOf"),
          Objects.requireNonNull(properties, "properties"),
          Objects.requireNonNull(localProperties, "localProperties"),
          Objects.requireNonNull(configMapKeys, "configMapKeys"),
          Objects.requireNonNull(secrets, "secrets"),
          Objects.requireNonNull(example, "example"),
          Objects.requireNonNull(persistence, "persistence"),
          Objects.requireNonNull(stacks, "stacks"));
    }
  }
}
