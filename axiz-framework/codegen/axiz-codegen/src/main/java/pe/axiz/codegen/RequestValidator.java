package pe.axiz.codegen;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import pe.axiz.codegen.model.Archetype;
import pe.axiz.codegen.model.Capability;
import pe.axiz.codegen.model.Catalog;
import pe.axiz.codegen.model.ProjectRequest;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.openapi.LintFinding;
import pe.axiz.codegen.openapi.OpenApiDocument;
import pe.axiz.codegen.openapi.OpenApiLinter;

/** Compatibility rules of archetype × framework × stack × persistence × capabilities. */
public final class RequestValidator {

  /**
   * Result of validating a request.
   *
   * @param archetype resolved archetype, when known
   * @param contract parsed contract, when present and readable
   * @param problems blocking problems
   * @param findings contract lint findings (errors block, warnings do not)
   */
  public record Result(
      @Nullable Archetype archetype,
      @Nullable OpenApiDocument contract,
      List<String> problems,
      List<LintFinding> findings) {
    public boolean valid() {
      return problems.isEmpty() && findings.stream().noneMatch(LintFinding::error);
    }
  }

  private final Catalog catalog;

  public RequestValidator(Catalog catalog) {
    this.catalog = catalog;
  }

  private List<String> needing(ProjectRequest r, String capability) {
    String store = Catalog.store(r.persistence(), r.stack());
    return r.capabilities().stream()
        .filter(
            c ->
                catalog.resolve(List.of(c), store).stream()
                    .anyMatch(x -> x.name().equals(capability)))
        .toList();
  }

  public Result validate(ProjectRequest r) {
    List<String> problems = new ArrayList<>();
    List<LintFinding> findings = new ArrayList<>();
    if (!Names.isArtifactId(r.name())) {
      problems.add("name must be kebab-case (letters, digits and dashes), e.g. payments-business");
    }
    if (!Names.isPackage(r.groupId())) {
      problems.add("groupId must be a valid Maven group, e.g. pe.axiz.payments");
    }
    if (!Names.isPackage(r.basePackage())) {
      problems.add("basePackage must be a valid Java package");
    }
    Archetype a = catalog.archetype(r.archetype()).orElse(null);
    OpenApiDocument contract = null;
    if (a == null) {
      problems.add(
          "Unknown archetype "
              + r.archetype()
              + "; available: "
              + String.join(", ", catalog.archetypes().stream().map(Archetype::name).toList()));
      return new Result(null, null, problems, findings);
    }
    if (!a.frameworks().contains(r.framework())) {
      problems.add(
          "Archetype "
              + a.name()
              + " supports frameworks "
              + a.frameworks().stream().map(f -> f.id()).toList());
    }
    if (!r.framework().stacks().contains(r.stack()) || !a.stacks().contains(r.stack())) {
      problems.add(
          "Stack "
              + r.stack().id()
              + " is not available for "
              + a.name()
              + " with "
              + r.framework().id());
    }
    if (!a.persistence().isEmpty() && !a.persistence().contains(r.persistence())) {
      problems.add("Archetype " + a.name() + " supports persistence " + a.persistence());
    }
    if (a.persistence().isEmpty() && !"none".equals(r.persistence())) {
      problems.add("Archetype " + a.name() + " has no persistence of its own");
    }
    boolean known = true;
    for (String c : r.capabilities()) {
      if (catalog.capability(c).isEmpty()) {
        problems.add("Unknown capability " + c);
        known = false;
      }
    }
    if (!a.isService() && !r.capabilities().isEmpty()) {
      problems.add("Capabilities apply to services only, not to " + a.kind() + " projects");
    }
    if (known && a.isService()) {
      String store = Catalog.store(r.persistence(), r.stack());
      List<Capability> capabilities =
          catalog.resolve(ProjectGenerator.capabilityNames(r, a), store);
      List<String> resolved = capabilities.stream().map(Capability::name).toList();
      if (resolved.contains("jpa") && !"aurora".equals(r.persistence())) {
        problems.add("Capabilities " + needing(r, "jpa") + " need Aurora: use persistence aurora");
      } else if (resolved.contains("jpa") && r.stack() == Stack.REACTIVE) {
        problems.add(
            "Capabilities "
                + needing(r, "jpa")
                + " need JPA, which blocks: on the reactive stack Aurora is used through r2dbc");
      }
      for (Capability c : capabilities) {
        if (!c.supports(store)) {
          problems.add(
              "Capability "
                  + c.name()
                  + " is not available on "
                  + Catalog.storeLabel(store)
                  + "; it supports "
                  + String.join(" or ", c.stores().stream().map(Catalog::storeLabel).toList()));
        }
      }
      if (resolved.contains("web") && a.deployment().equals("cronjob")) {
        problems.add("Archetype " + a.name() + " runs as a CronJob and has no web server");
      }
    }
    if (a.rest()) {
      if (r.openApi() == null || r.openApi().isBlank()) {
        problems.add(
            "Archetype " + a.name() + " exposes REST: an OpenAPI 3.1 contract is required");
      } else {
        try {
          contract = OpenApiDocument.parse(r.openApi());
          findings.addAll(OpenApiLinter.lint(contract));
        } catch (IllegalArgumentException e) {
          problems.add(e.getMessage());
        }
      }
    }
    return new Result(a, contract, problems, findings);
  }
}
