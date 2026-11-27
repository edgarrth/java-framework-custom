package pe.axiz.codegen;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import pe.axiz.codegen.asyncapi.AsyncApiDocument;
import pe.axiz.codegen.model.Archetype;
import pe.axiz.codegen.model.Capability;
import pe.axiz.codegen.model.Catalog;
import pe.axiz.codegen.model.ProjectRequest;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.model.Structure;
import pe.axiz.codegen.openapi.LintFinding;
import pe.axiz.codegen.openapi.OpenApiDocument;
import pe.axiz.codegen.openapi.OpenApiLinter;

/** Reglas de compatibilidad de arquetipo, estructura, stack, persistencia y capacidades. */
public final class RequestValidator {

  /**
   * Resultado de validar una solicitud.
   *
   * @param archetype arquetipo resuelto, cuando se conoce
   * @param contract contrato analizado, cuando está presente y es legible
   * @param problems problemas bloqueantes
   * @param findings hallazgos de lint del contrato (los errores bloquean, las advertencias no)
   */
  public record Result(
      @Nullable Archetype archetype,
      @Nullable OpenApiDocument contract,
      List<String> problems,
      List<LintFinding> findings) {
    public Result {
      findings = List.copyOf(findings);
      problems = List.copyOf(problems);
    }

    public boolean valid() {
      return problems.isEmpty() && findings.stream().noneMatch(LintFinding::error);
    }
  }

  /** Descripción más larga aceptada: una línea en cada archivo generado. */
  static final int MAX_DESCRIPTION = 200;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Catalog owns defensive snapshots and exposes no mutator; SpotBugs does not infer "
              + "its immutability.")
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
      problems.add("name must be kebab-case (letters, digits and dashes), e.g. payment-orders");
    }
    if (!Names.isPackage(r.groupId())) {
      problems.add("groupId must be a valid Maven group, e.g. pe.axiz.payments");
    }
    if (!Names.isPackage(r.basePackage())) {
      problems.add("basePackage must be a valid Java package");
    }
    if (r.description().length() > MAX_DESCRIPTION
        || r.description().chars().anyMatch(Character::isISOControl)) {
      // una línea de texto: termina en POM, Javadoc, YAML y Markdown (la puntuación está permitida)
      problems.add(
          "description must be a single line of at most " + MAX_DESCRIPTION + " characters");
    }
    boolean consumesEvents = false;
    String asyncApi = r.asyncApi();
    if (asyncApi != null && !asyncApi.isBlank()) {
      try {
        consumesEvents = !AsyncApiDocument.parse(asyncApi).consumed().isEmpty();
      } catch (IllegalArgumentException problem) {
        problems.add("Invalid AsyncAPI: " + problem.getMessage());
      }
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
    if (a.isService() && !ServiceRoles.validName(a.name(), r.name()))
      problems.add("Service name does not follow the convention for " + a.name());
    if (!a.extensions().containsAll(r.extensions())
        || (r.cqrs() && r.structure() != Structure.HEXAGONAL)) {
      problems.add(
          "Extensions are incompatible with archetype or structure; "
              + "CQRS requires hexagonal business or event-processor");
    }
    if (!a.structures().contains(r.structure())) {
      problems.add(
          "Archetype "
              + a.name()
              + " supports structures "
              + a.structures().stream().map(f -> f.id()).toList());
    }
    if (!r.structure().stacks().contains(r.stack()) || !a.stacks().contains(r.stack())) {
      problems.add(
          "Stack "
              + r.stack().id()
              + " is not available for "
              + a.name()
              + " with "
              + r.structure().id());
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
    String declaredOpenApi = r.openApi();
    if (!a.isService()
        && ((declaredOpenApi != null && !declaredOpenApi.isBlank())
            || (asyncApi != null && !asyncApi.isBlank())))
      problems.add("OpenAPI and AsyncAPI contracts apply to services only");
    if (!a.isService() && !r.capabilities().isEmpty()) {
      problems.add("Capabilities apply to services only, not to " + a.kind() + " projects");
    }
    if (known && a.isService()) {
      String store = Catalog.store(r.persistence(), r.stack());
      List<Capability> capabilities =
          catalog.resolve(ProjectGenerator.capabilityNames(catalog, r, a), store);
      problems.addAll(catalog.missingAlternatives(capabilities));
      if (consumesEvents
          && capabilities.stream()
              .noneMatch(
                  capability ->
                      Set.of("messaging-kafka", "messaging-sqs").contains(capability.name())))
        problems.add(
            "Consuming AsyncAPI events requires a messaging capability with consumers:"
                + " messaging-kafka or messaging-sqs");
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
        if (!c.supports(r.stack()))
          problems.add("Capability " + c.name() + " does not support stack " + r.stack().id());
        if (Set.of("ux", "core", "gateway").contains(a.name())
            && Set.of("messaging", "outbox", "inbox", "notification").contains(c.name())) {
          problems.add("Archetype " + a.name() + " cannot use domain messaging");
        }
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
      String openApi = r.openApi();
      if (openApi == null || openApi.isBlank()) {
        problems.add(
            "Archetype " + a.name() + " exposes REST: an OpenAPI 3.1 contract is required");
      } else {
        try {
          contract = OpenApiDocument.parse(openApi);
          findings.addAll(OpenApiLinter.lint(contract));
        } catch (IllegalArgumentException e) {
          problems.add(e.getMessage());
        }
      }
    }
    return new Result(a, contract, problems, findings);
  }
}
