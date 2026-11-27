package pe.axiz.codegen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import pe.axiz.codegen.model.Archetype;
import pe.axiz.codegen.model.ProjectRequest;
import pe.axiz.codegen.openapi.ApiOperation;
import pe.axiz.codegen.openapi.ContractDiff;
import pe.axiz.codegen.openapi.LintFinding;
import pe.axiz.codegen.openapi.OpenApiDocument;
import pe.axiz.codegen.openapi.OpenApiLinter;
import pe.axiz.codegen.output.SafePaths;
import pe.axiz.codegen.project.ProjectLayout;
import pe.axiz.codegen.project.ProjectManifest;
import pe.axiz.codegen.render.JavaFormatter;
import pe.axiz.codegen.render.TemplateSet;

/**
 * {@code axiz contract sync}: tras editar {@code src/main/openapi/openapi.yaml}, genera los
 * esqueletos de las operaciones nuevas, propone controladores actualizados (archivos {@code
 * .axiz-new} junto a los existentes, sin sobrescribir nunca código), informa las operaciones
 * eliminadas y verifica la compatibilidad hacia atrás contra el snapshot del último contrato
 * sincronizado.
 */
public final class ContractSync {

  /** Snapshot del contrato en la última generación o sincronización. */
  public static final String SNAPSHOT = ".axiz/openapi.snapshot.yaml";

  /** Sufijo de las versiones propuestas de archivos existentes. */
  public static final String PROPOSAL = ".axiz-new";

  /**
   * Resultado de una sincronización.
   *
   * @param added operaciones nuevas en el contrato
   * @param removed operaciones ausentes del contrato (su código queda a cargo del equipo)
   * @param created archivos creados
   * @param proposed propuestas escritas junto a archivos existentes ({@code .axiz-new})
   * @param changes cambios de compatibilidad respecto del snapshot previo
   * @param findings hallazgos de lint del contrato
   * @param applied indica si se escribieron archivos (false cuando lo bloquean cambios
   *     incompatibles o el lint)
   */
  public record Result(
      List<String> added,
      List<String> removed,
      List<String> created,
      List<String> proposed,
      List<ContractDiff.Change> changes,
      List<LintFinding> findings,
      boolean applied) {
    public Result {
      added = List.copyOf(added);
      changes = List.copyOf(changes);
      created = List.copyOf(created);
      findings = List.copyOf(findings);
      proposed = List.copyOf(proposed);
      removed = List.copyOf(removed);
    }
  }

  private final ProjectGenerator generator;

  public ContractSync(ProjectGenerator generator) {
    this.generator = generator;
  }

  /**
   * Sincroniza el proyecto de {@code dir} con su contrato.
   *
   * @param allowBreaking aplica incluso cuando el contrato rompe a los consumidores existentes
   */
  public Result sync(Path dir, boolean allowBreaking) throws IOException {
    dir = dir.toAbsolutePath().normalize();
    ProjectManifest manifest = ProjectManifest.read(dir);
    ProjectLayout layout = ProjectLayout.read(dir, manifest);
    String contractPath = manifest.openapi();
    if (contractPath == null) {
      throw new IllegalArgumentException("The project has no OpenAPI contract");
    }
    String text = Files.readString(SafePaths.target(dir, contractPath), StandardCharsets.UTF_8);
    OpenApiDocument contract = OpenApiDocument.parse(text);
    List<LintFinding> findings = new ArrayList<>(OpenApiLinter.lint(contract));
    Path snapshot = SafePaths.target(dir, SNAPSHOT);
    List<ContractDiff.Change> changes =
        Files.isRegularFile(snapshot)
            ? ContractDiff.compare(
                OpenApiDocument.parse(Files.readString(snapshot, StandardCharsets.UTF_8)), contract)
            : List.of();
    List<String> ids = contract.operations().stream().map(ApiOperation::operationId).toList();
    List<String> added = ids.stream().filter(id -> !manifest.operations().contains(id)).toList();
    List<String> removed = manifest.operations().stream().filter(id -> !ids.contains(id)).toList();
    boolean blocked =
        findings.stream().anyMatch(LintFinding::error)
            || (ContractDiff.hasBreaking(changes) && !allowBreaking);
    if (blocked) {
      return new Result(added, removed, List.of(), List.of(), changes, findings, false);
    }

    Archetype archetype =
        generator
            .catalog()
            .archetype(manifest.archetype())
            .orElseThrow(
                () -> new IllegalArgumentException("Unknown archetype " + manifest.archetype()));
    ProjectRequest request =
        ProjectRequest.builder()
            .name(manifest.name())
            .groupId(manifest.groupId())
            .basePackage(manifest.basePackage())
            .description("")
            .archetype(manifest.archetype())
            .structure(manifest.structure())
            .stack(manifest.stack())
            .persistence(manifest.persistence())
            .capabilities(List.of())
            .openApi(text)
            .asyncApi(null)
            .frameworkVersion(manifest.axizVersion())
            .extensions(manifest.extensions())
            .build();
    Map<String, Object> context = generator.context(request, archetype, contract);
    Map<String, byte[]> rendered =
        JavaFormatter.formatAll(TemplateSet.load("service").render(context));

    List<String> addedClasses = added.stream().map(Names::pascal).toList();
    List<String> created = new ArrayList<>();
    List<String> proposed = new ArrayList<>();
    Map<String, byte[]> selected = new LinkedHashMap<>();
    Set<String> adapterPaths = new LinkedHashSet<>();
    for (Map.Entry<String, byte[]> e : rendered.entrySet()) {
      String path = e.getKey();
      String file = path.substring(path.lastIndexOf('/') + 1);
      boolean operationFile =
          addedClasses.stream()
                  .anyMatch(
                      c ->
                          file.equals(c + "ApplicationMappingTest.java")
                              || file.equals(c + "UseCase.java")
                              || file.equals(c + "Command.java")
                              || file.equals(c + "Query.java")
                              || file.equals(c + "Handler.java"))
              || added.stream().anyMatch(id -> path.endsWith("contract-examples/" + id + ".json"))
              || path.contains("/response-examples/");
      boolean adapterFile =
          path.contains("/application/model/")
              || (path.contains("/application/command/") && file.endsWith("Command.java"))
              || (path.contains("/application/query/") && file.endsWith("Query.java"))
              || file.equals("CqrsAuthorizationConfiguration.java")
              || file.endsWith("AuthorizationTest.java")
              || file.endsWith("ControllerResponseTest.java")
              || file.endsWith("ApplicationModelTest.java")
              || file.endsWith("ApplicationTest.java")
              || file.endsWith("ApplicationTestSupport.java")
              || file.equals("SensitiveDataCanaryTest.java")
              || file.endsWith("Controller.java")
              || file.equals("ContractExamplesTest.java")
              || (file.endsWith("Service.java") && path.contains("/service/"))
              || (file.endsWith("Composition.java") && path.contains("/composition/"));
      if (!operationFile && !adapterFile) {
        continue;
      }
      String relative = layout.relative(path);
      selected.put(relative, e.getValue());
      if (adapterFile) adapterPaths.add(relative);
    }
    SafePaths.target(dir, ProjectManifest.PATH);
    for (String path : selected.keySet()) {
      SafePaths.target(dir, path);
      SafePaths.target(dir, path + PROPOSAL);
    }
    for (Map.Entry<String, byte[]> e : selected.entrySet()) {
      String path = e.getKey();
      boolean adapterFile = adapterPaths.contains(path);
      Path target = SafePaths.target(dir, path);
      boolean ownedTest = path.endsWith("Test.java") || path.contains("/response-examples/");
      if (ownedTest
          && (!Files.exists(target) || !Arrays.equals(Files.readAllBytes(target), e.getValue()))) {
        SafePaths.write(dir, path + PROPOSAL, e.getValue(), true);
        proposed.add(path + PROPOSAL);
      } else if (!Files.exists(target)) {
        SafePaths.write(dir, path, e.getValue(), false);
        created.add(path);
      } else if (adapterFile && !Arrays.equals(Files.readAllBytes(target), e.getValue())) {
        SafePaths.write(dir, path + PROPOSAL, e.getValue(), true);
        proposed.add(path + PROPOSAL);
      }
    }
    SafePaths.write(dir, SNAPSHOT, text.getBytes(StandardCharsets.UTF_8), true);
    manifest.withOperations(ids).write(dir);
    return new Result(added, removed, created, proposed, changes, findings, true);
  }

  /** Ids de operación por etiqueta, para los reportes. */
  public static Map<String, List<String>> byTag(OpenApiDocument contract) {
    Map<String, List<String>> out = new LinkedHashMap<>();
    contract
        .operations()
        .forEach(op -> out.computeIfAbsent(op.tag(), t -> new ArrayList<>()).add(op.operationId()));
    return out;
  }
}
