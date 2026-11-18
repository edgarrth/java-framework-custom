package pe.axiz.codegen.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A capability that the initializr and {@code axiz add} can add to a service.
 *
 * @param name capability id, e.g. {@code idempotency-redis}
 * @param starter axiz starter artifact
 * @param description what it brings
 * @param dependencies extra artifacts as {@code groupId:artifactId}
 * @param requires capabilities added together with this one
 * @param requiresAnyOf at least one of these explicitly selected capabilities is required
 * @param properties {@code application.yaml} entries (flat keys)
 * @param localProperties {@code application-local.yaml} entries (flat keys)
 * @param configMapKeys keys expected in the service ConfigMap
 * @param secrets keys of the service secret
 * @param example usage example shown after adding it
 * @param persistence per store ({@code aurora} over JDBC, {@code r2dbc} for Aurora from the
 *     reactive stack, {@code dynamodb}; see {@link Catalog#store}) the extra properties,
 *     dependencies and required capabilities of the store; empty when the capability needs no
 *     store, and a store missing from a non-empty map is not supported
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
   * Store-specific additions.
   *
   * @param properties extra {@code application.yaml} entries
   * @param dependencies extra artifacts ({@code groupId:artifactId})
   * @param requires capabilities added together with this one
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

  /** Whether the capability works with the given store. */
  public boolean supports(String store) {
    return persistence.isEmpty() || persistence.containsKey(store);
  }

  /** Stores the capability supports, in the canonical order of {@link Catalog#STORES}. */
  public List<String> stores() {
    return Catalog.STORES.stream().filter(persistence::containsKey).toList();
  }

  /** Required capabilities including the store-specific ones. */
  public List<String> requires(String store) {
    List<String> out = new ArrayList<>(requires);
    Store s = persistence.get(store);
    if (s != null) {
      out.addAll(s.requires());
    }
    return out;
  }

  /** Properties including the store-specific ones. */
  public Map<String, Object> properties(String persistenceOption) {
    Map<String, Object> out = new LinkedHashMap<>(properties);
    Store store = persistence.get(persistenceOption);
    if (store != null) {
      out.putAll(store.properties());
    }
    return out;
  }

  /** Dependencies including the store-specific ones. */
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

  /** Named construction; required values and invariants are checked by build. */
  public static Builder builder() {
    return new Builder();
  }

  /** Mutable construction state; use one builder per operation, never as shared state. */
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
