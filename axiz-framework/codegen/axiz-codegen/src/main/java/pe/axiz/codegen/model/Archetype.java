package pe.axiz.codegen.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Role of a service in the ecosystem and the defaults it preselects.
 *
 * @param name archetype id
 * @param role short description
 * @param frameworks allowed styles, the first one is the default
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
    List<Framework> frameworks,
    List<Stack> stacks,
    List<String> persistence,
    List<String> starters,
    String deployment,
    boolean rest,
    String kind,
    Map<String, Object> properties) {

  public Archetype {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(role, "role");
    if (frameworks.isEmpty() || stacks.isEmpty()) {
      throw new IllegalArgumentException("Archetype " + name + " needs frameworks and stacks");
    }
    frameworks = List.copyOf(frameworks);
    stacks = List.copyOf(stacks);
    persistence = List.copyOf(persistence);
    starters = List.copyOf(starters);
    properties = Catalog.ordered(properties);
  }

  public Framework defaultFramework() {
    return frameworks.get(0);
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
}
