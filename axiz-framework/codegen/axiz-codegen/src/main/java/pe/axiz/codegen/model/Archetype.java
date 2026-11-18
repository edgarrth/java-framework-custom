package pe.axiz.codegen.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Role of a service in the ecosystem and the defaults it preselects.
 *
 * @param name archetype id
 * @param role short description
 * @param structures allowed styles, the first one is the default
 * @param stacks allowed stacks, the first one is the default
 * @param persistence allowed persistence options ({@code none}, {@code aurora}, {@code dynamodb}),
 *     first is default; empty when the archetype has no persistence
 * @param starters capabilities always included
 * @param deployment {@code deployment}, {@code cronjob} or {@code none} (platform archetypes)
 * @param rest whether it exposes a REST API (and therefore needs an OpenAPI contract)
 * @param kind kind of project: service, library or starter
 * @param properties {@code application.yaml} defaults of the archetype (flat keys)
 */
public record Archetype(
    String name,
    String role,
    List<Structure> structures,
    List<Stack> stacks,
    List<String> persistence,
    List<String> starters,
    String deployment,
    boolean rest,
    String kind,
    Map<String, Object> properties,
    List<Extension> extensions) {

  public Archetype {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(role, "role");
    if (structures.isEmpty() || stacks.isEmpty()) {
      throw new IllegalArgumentException("Archetype " + name + " needs structures and stacks");
    }
    extensions = List.copyOf(extensions);
    structures = List.copyOf(structures);
    stacks = List.copyOf(stacks);
    persistence = List.copyOf(persistence);
    starters = List.copyOf(starters);
    properties = Catalog.ordered(properties);
  }

  public Structure defaultStructure() {
    return structures.get(0);
  }

  public Stack defaultStack() {
    return stacks.get(0);
  }

  public String defaultPersistence() {
    return persistence.isEmpty() ? "none" : persistence.get(0);
  }

  public boolean isService() {
    return "service".equals(kind);
  }

  @Override
  public Map<String, Object> properties() {
    return Snapshots.properties(properties);
  }

  /** Named construction; required values and invariants are checked by build. */
  public static Builder builder() {
    return new Builder();
  }

  /** Mutable construction state; use one builder per operation, never as shared state. */
  public static final class Builder {
    private @Nullable String name = null;
    private @Nullable String role = null;
    private @Nullable List<Structure> structures = List.of();
    private @Nullable List<Stack> stacks = List.of();
    private @Nullable List<String> persistence = List.of();
    private @Nullable List<String> starters = List.of();
    private @Nullable String deployment = null;
    private boolean rest = false;
    private @Nullable String kind = null;
    private @Nullable Map<String, Object> properties = Map.of();
    private @Nullable List<Extension> extensions = List.of();

    private Builder() {}

    public Builder name(String value) {
      this.name = value;
      return this;
    }

    public Builder role(String value) {
      this.role = value;
      return this;
    }

    public Builder structures(List<Structure> value) {
      this.structures = List.copyOf(value);
      return this;
    }

    public Builder stacks(List<Stack> value) {
      this.stacks = List.copyOf(value);
      return this;
    }

    public Builder persistence(List<String> value) {
      this.persistence = List.copyOf(value);
      return this;
    }

    public Builder starters(List<String> value) {
      this.starters = List.copyOf(value);
      return this;
    }

    public Builder deployment(String value) {
      this.deployment = value;
      return this;
    }

    public Builder rest(boolean value) {
      this.rest = value;
      return this;
    }

    public Builder kind(String value) {
      this.kind = value;
      return this;
    }

    public Builder properties(Map<String, Object> value) {
      this.properties = Map.copyOf(value);
      return this;
    }

    public Builder extensions(List<Extension> value) {
      this.extensions = List.copyOf(value);
      return this;
    }

    public Archetype build() {
      return new Archetype(
          Objects.requireNonNull(name, "name"),
          Objects.requireNonNull(role, "role"),
          Objects.requireNonNull(structures, "structures"),
          Objects.requireNonNull(stacks, "stacks"),
          Objects.requireNonNull(persistence, "persistence"),
          Objects.requireNonNull(starters, "starters"),
          Objects.requireNonNull(deployment, "deployment"),
          rest,
          Objects.requireNonNull(kind, "kind"),
          Objects.requireNonNull(properties, "properties"),
          Objects.requireNonNull(extensions, "extensions"));
    }
  }
}
