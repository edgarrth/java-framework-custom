package pe.axiz.codegen.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Rol de un servicio en el ecosistema y los valores por defecto que preselecciona.
 *
 * @param name id del arquetipo
 * @param role descripción breve
 * @param structures estilos permitidos; el primero es el valor por defecto
 * @param stacks stacks permitidos; el primero es el valor por defecto
 * @param persistence opciones de persistencia permitidas ({@code none}, {@code aurora}, {@code
 *     dynamodb}); la primera es el valor por defecto; vacío cuando el arquetipo no tiene
 *     persistencia
 * @param starters capacidades que siempre se incluyen
 * @param deployment {@code deployment}, {@code cronjob} o {@code none} (arquetipos de plataforma)
 * @param rest indica si expone una API REST (y, por lo tanto, necesita un contrato OpenAPI)
 * @param kind tipo de proyecto: servicio, biblioteca o starter
 * @param properties valores por defecto de {@code application.yaml} del arquetipo (claves planas)
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

  /** Construcción con nombre; build verifica los valores obligatorios y las invariantes. */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Estado mutable de construcción; se usa un builder por operación, nunca como estado compartido.
   */
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
