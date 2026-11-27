package pe.axiz.initializr.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import pe.axiz.codegen.model.Archetype;
import pe.axiz.codegen.model.Catalog;
import pe.axiz.codegen.model.Extension;
import pe.axiz.codegen.model.ProjectRequest;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.model.Structure;
import pe.axiz.codegen.openapi.OpenApiDocument;

/**
 * Solicitud de generación de la API (los mismos campos que la CLI). La estructura, el stack y la
 * persistencia faltantes toman los valores por defecto del arquetipo.
 *
 * @param name nombre del proyecto en kebab-case
 * @param groupId grupo de Maven
 * @param basePackage paquete base
 * @param description descripción de una línea
 * @param archetype id del arquetipo
 * @param structure id del estilo
 * @param stack id del stack
 * @param persistence opción de persistencia
 * @param capabilities capacidades adicionales
 * @param openApi texto del contrato OpenAPI
 * @param asyncApi texto del contrato AsyncAPI
 * @param frameworkVersion versión de axiz (por defecto, la del initializr)
 */
public record ProjectRequestBody(
    @NotBlank @Size(max = 60) @Pattern(regexp = "^[a-z][a-z0-9]*(-[a-z0-9]+)*$") String name,
    @NotBlank @Size(max = 120) String groupId,
    @NotBlank @Size(max = 120) String basePackage,
    @Nullable @Size(max = 300) String description,
    @NotBlank String archetype,
    @Nullable String structure,
    @Nullable String stack,
    @Nullable String persistence,
    @Nullable @Size(max = 30) List<String> capabilities,
    @Nullable @Size(max = OpenApiDocument.MAX_SIZE) String openApi,
    @Nullable @Size(max = OpenApiDocument.MAX_SIZE) String asyncApi,
    @Nullable @Pattern(regexp = "^\\d+\\.\\d+\\.\\d+(-[A-Za-z0-9.]+)?$") String frameworkVersion,
    @Nullable @Size(max = 1) List<String> extensions) {
  public ProjectRequestBody {
    extensions = extensions == null ? List.of() : List.copyOf(extensions);
    capabilities = capabilities == null ? null : List.copyOf(capabilities);
  }

  /** La solicitud para el motor, con los valores por defecto resueltos a partir del arquetipo. */
  public ProjectRequest toRequest(Catalog catalog, String defaultVersion) {
    Archetype a =
        catalog
            .archetype(archetype)
            .orElseThrow(() -> new IllegalArgumentException("Unknown archetype " + archetype));
    return ProjectRequest.builder()
        .name(name)
        .groupId(groupId)
        .basePackage(basePackage)
        .description(description == null ? "" : description)
        .archetype(a.name())
        .structure(
            structure == null || structure.isBlank()
                ? a.defaultStructure()
                : Structure.of(structure.toLowerCase(Locale.ROOT)))
        .stack(
            stack == null || stack.isBlank()
                ? a.defaultStack()
                : Stack.of(stack.toLowerCase(Locale.ROOT)))
        .persistence(
            persistence == null || persistence.isBlank() ? a.defaultPersistence() : persistence)
        .capabilities(capabilities == null ? List.of() : capabilities)
        .openApi(openApi)
        .asyncApi(asyncApi)
        .frameworkVersion(frameworkVersion == null ? defaultVersion : frameworkVersion)
        .extensions(
            (extensions == null ? List.<String>of() : extensions)
                .stream().map(Extension::of).toList())
        .build();
  }
}
