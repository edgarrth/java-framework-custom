package pe.axiz.codegen.model;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What to generate.
 *
 * @param name service name in kebab-case, e.g. {@code payments-business}
 * @param groupId Maven group
 * @param basePackage base Java package
 * @param description one-line description
 * @param archetype archetype id
 * @param framework style
 * @param stack stack
 * @param persistence {@code none}, {@code aurora} or {@code dynamodb}
 * @param capabilities extra capability ids
 * @param openApi OpenAPI 3.1 contract (YAML or JSON); required for archetypes exposing REST
 * @param asyncApi optional AsyncAPI contract
 * @param frameworkVersion axiz version the project builds on
 */
public record ProjectRequest(
    String name,
    String groupId,
    String basePackage,
    String description,
    String archetype,
    Framework framework,
    Stack stack,
    String persistence,
    List<String> capabilities,
    @Nullable String openApi,
    @Nullable String asyncApi,
    String frameworkVersion) {

  public ProjectRequest {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(groupId, "groupId");
    Objects.requireNonNull(basePackage, "basePackage");
    Objects.requireNonNull(archetype, "archetype");
    Objects.requireNonNull(framework, "framework");
    Objects.requireNonNull(stack, "stack");
    Objects.requireNonNull(persistence, "persistence");
    capabilities = List.copyOf(capabilities);
    Objects.requireNonNull(frameworkVersion, "frameworkVersion");
  }
}
