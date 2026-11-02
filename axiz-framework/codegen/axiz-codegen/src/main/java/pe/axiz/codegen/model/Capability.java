package pe.axiz.codegen.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A capability that the initializr and {@code axiz add} can add to a service.
 *
 * @param name capability id, e.g. {@code idempotency-redis}
 * @param starter axiz starter artifact
 * @param description what it brings
 * @param dependencies extra artifacts as {@code groupId:artifactId}
 * @param requires capabilities added together with this one
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
   * @param requires capabilities added together with this one on this store
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
    properties = Catalog.ordered(properties);
    localProperties = Catalog.ordered(localProperties);
    configMapKeys = List.copyOf(configMapKeys);
    secrets = List.copyOf(secrets);
    persistence = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(persistence));
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
    List<String> out = new java.util.ArrayList<>(requires);
    Store s = persistence.get(store);
    if (s != null) {
      out.addAll(s.requires());
    }
    return out;
  }

  /** Properties including the store-specific ones. */
  public Map<String, Object> properties(String persistenceOption) {
    Map<String, Object> out = new java.util.LinkedHashMap<>(properties);
    Store store = persistence.get(persistenceOption);
    if (store != null) {
      out.putAll(store.properties());
    }
    return out;
  }

  /** Dependencies including the store-specific ones. */
  public List<String> dependencies(String persistenceOption) {
    List<String> out = new java.util.ArrayList<>(dependencies);
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
}
