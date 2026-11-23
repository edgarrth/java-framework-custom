package pe.axiz.codegen.model;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Qué se genera.
 *
 * @param name nombre del servicio en kebab-case, p. ej., {@code payment-orders}
 * @param groupId grupo de Maven
 * @param basePackage paquete Java base
 * @param description descripción de una línea
 * @param archetype id del arquetipo
 * @param structure estilo
 * @param stack stack
 * @param persistence {@code none}, {@code aurora} o {@code dynamodb}
 * @param capabilities ids de capacidades adicionales
 * @param openApi contrato OpenAPI 3.1 (YAML o JSON); obligatorio para los arquetipos que exponen
 *     REST
 * @param asyncApi contrato AsyncAPI opcional
 * @param frameworkVersion versión de axiz sobre la que se construye el proyecto
 */
public record ProjectRequest(
    String name,
    String groupId,
    String basePackage,
    String description,
    String archetype,
    Structure structure,
    Stack stack,
    String persistence,
    List<String> capabilities,
    @Nullable String openApi,
    @Nullable String asyncApi,
    String frameworkVersion,
    List<Extension> extensions) {

  public boolean cqrs() {
    return extensions.contains(Extension.CQRS);
  }

  public ProjectRequest {
    extensions = List.copyOf(extensions);
    if (new HashSet<>(extensions).size() != extensions.size())
      throw new IllegalArgumentException("Duplicate service extension");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(groupId, "groupId");
    Objects.requireNonNull(basePackage, "basePackage");
    Objects.requireNonNull(archetype, "archetype");
    Objects.requireNonNull(structure, "structure");
    Objects.requireNonNull(stack, "stack");
    Objects.requireNonNull(persistence, "persistence");
    capabilities = List.copyOf(capabilities);
    FrameworkVersions.requireSupported(frameworkVersion);
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
    private @Nullable String groupId = null;
    private @Nullable String basePackage = null;
    private @Nullable String description = null;
    private @Nullable String archetype = null;
    private @Nullable Structure structure = null;
    private @Nullable Stack stack = null;
    private @Nullable String persistence = null;
    private @Nullable List<String> capabilities = List.of();
    private @Nullable String openApi = null;
    private @Nullable String asyncApi = null;
    private @Nullable String frameworkVersion = null;
    private @Nullable List<Extension> extensions = List.of();

    private Builder() {}

    public Builder name(String value) {
      this.name = value;
      return this;
    }

    public Builder groupId(String value) {
      this.groupId = value;
      return this;
    }

    public Builder basePackage(String value) {
      this.basePackage = value;
      return this;
    }

    public Builder description(String value) {
      this.description = value;
      return this;
    }

    public Builder archetype(String value) {
      this.archetype = value;
      return this;
    }

    public Builder structure(Structure value) {
      this.structure = value;
      return this;
    }

    public Builder stack(Stack value) {
      this.stack = value;
      return this;
    }

    public Builder persistence(String value) {
      this.persistence = value;
      return this;
    }

    public Builder capabilities(List<String> value) {
      this.capabilities = List.copyOf(value);
      return this;
    }

    public Builder openApi(@Nullable String value) {
      this.openApi = value;
      return this;
    }

    public Builder asyncApi(@Nullable String value) {
      this.asyncApi = value;
      return this;
    }

    public Builder frameworkVersion(String value) {
      this.frameworkVersion = value;
      return this;
    }

    public Builder extensions(List<Extension> value) {
      this.extensions = List.copyOf(value);
      return this;
    }

    public ProjectRequest build() {
      return new ProjectRequest(
          Objects.requireNonNull(name, "name"),
          Objects.requireNonNull(groupId, "groupId"),
          Objects.requireNonNull(basePackage, "basePackage"),
          Objects.requireNonNull(description, "description"),
          Objects.requireNonNull(archetype, "archetype"),
          Objects.requireNonNull(structure, "structure"),
          Objects.requireNonNull(stack, "stack"),
          Objects.requireNonNull(persistence, "persistence"),
          Objects.requireNonNull(capabilities, "capabilities"),
          openApi,
          asyncApi,
          Objects.requireNonNull(frameworkVersion, "frameworkVersion"),
          Objects.requireNonNull(extensions, "extensions"));
    }
  }
}
