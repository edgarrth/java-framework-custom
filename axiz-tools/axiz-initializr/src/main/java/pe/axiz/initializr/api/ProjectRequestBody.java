package pe.axiz.initializr.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import pe.axiz.codegen.model.Archetype;
import pe.axiz.codegen.model.Catalog;
import pe.axiz.codegen.model.Framework;
import pe.axiz.codegen.model.ProjectRequest;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.openapi.OpenApiDocument;

/**
 * Generation request of the API (same fields as the CLI). Missing framework, stack and persistence
 * take the archetype defaults.
 *
 * @param name project name in kebab-case
 * @param groupId Maven group
 * @param basePackage base package
 * @param description one-line description
 * @param archetype archetype id
 * @param framework style id
 * @param stack stack id
 * @param persistence persistence option
 * @param capabilities extra capabilities
 * @param openApi OpenAPI contract text
 * @param asyncApi AsyncAPI contract text
 * @param frameworkVersion axiz version (default: the initializr's)
 */
public record ProjectRequestBody(
    @NotBlank @Size(max = 60) @Pattern(regexp = "^[a-z][a-z0-9]*(-[a-z0-9]+)*$") String name,
    @NotBlank @Size(max = 120) String groupId,
    @NotBlank @Size(max = 120) String basePackage,
    @Nullable @Size(max = 300) String description,
    @NotBlank String archetype,
    @Nullable String framework,
    @Nullable String stack,
    @Nullable String persistence,
    @Nullable @Size(max = 30) List<String> capabilities,
    @Nullable @Size(max = OpenApiDocument.MAX_SIZE) String openApi,
    @Nullable @Size(max = OpenApiDocument.MAX_SIZE) String asyncApi,
    @Nullable @Pattern(regexp = "^\\d+\\.\\d+\\.\\d+(-[A-Za-z0-9.]+)?$") String frameworkVersion) {

  /** The engine request, defaults resolved from the archetype. */
  public ProjectRequest toRequest(Catalog catalog, String defaultVersion) {
    Archetype a =
        catalog
            .archetype(archetype)
            .orElseThrow(() -> new IllegalArgumentException("Unknown archetype " + archetype));
    return new ProjectRequest(
        name,
        groupId,
        basePackage,
        description == null ? "" : description,
        a.name(),
        framework == null || framework.isBlank()
            ? a.defaultFramework()
            : Framework.of(framework.toLowerCase(Locale.ROOT)),
        stack == null || stack.isBlank()
            ? a.defaultStack()
            : Stack.of(stack.toLowerCase(Locale.ROOT)),
        persistence == null || persistence.isBlank() ? a.defaultPersistence() : persistence,
        capabilities == null ? List.of() : capabilities,
        openApi,
        asyncApi,
        frameworkVersion == null ? defaultVersion : frameworkVersion);
  }
}
