package pe.axiz.codegen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import pe.axiz.codegen.model.Archetype;
import pe.axiz.codegen.model.ProjectRequest;
import pe.axiz.codegen.openapi.ApiOperation;
import pe.axiz.codegen.openapi.ContractDiff;
import pe.axiz.codegen.openapi.LintFinding;
import pe.axiz.codegen.openapi.OpenApiDocument;
import pe.axiz.codegen.openapi.OpenApiLinter;
import pe.axiz.codegen.output.SafePaths;
import pe.axiz.codegen.project.ProjectManifest;
import pe.axiz.codegen.render.JavaFormatter;
import pe.axiz.codegen.render.TemplateSet;

/**
 * {@code axiz contract sync}: after editing {@code src/main/openapi/openapi.yaml}, generates the
 * skeletons of new operations, proposes updated controllers ({@code .axiz-new} files next to the
 * existing ones, never overwriting code), reports removed operations and checks backward
 * compatibility against the snapshot of the last synced contract.
 */
public final class ContractSync {

  /** Snapshot of the contract at the last generation or sync. */
  public static final String SNAPSHOT = ".axiz/openapi.snapshot.yaml";

  /** Suffix of proposed versions of existing files. */
  public static final String PROPOSAL = ".axiz-new";

  /**
   * Outcome of a sync.
   *
   * @param added operations new in the contract
   * @param removed operations no longer in the contract (their code is left for the team)
   * @param created files created
   * @param proposed proposals written next to existing files ({@code .axiz-new})
   * @param changes compatibility changes against the previous snapshot
   * @param findings lint findings of the contract
   * @param applied whether files were written (false when blocked by breaking changes or lint)
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
   * Synchronizes the project in {@code dir} with its contract.
   *
   * @param allowBreaking apply even when the contract breaks existing consumers
   */
  public Result sync(Path dir, boolean allowBreaking) throws IOException {
    ProjectManifest manifest = ProjectManifest.read(dir);
    if (manifest.openapi() == null) {
      throw new IllegalArgumentException("The project has no OpenAPI contract");
    }
    String text = Files.readString(dir.resolve(manifest.openapi()), StandardCharsets.UTF_8);
    OpenApiDocument contract = OpenApiDocument.parse(text);
    List<LintFinding> findings = OpenApiLinter.lint(contract);
    Path snapshot = dir.resolve(SNAPSHOT);
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
        new ProjectRequest(
            manifest.name(),
            manifest.groupId(),
            manifest.basePackage(),
            "",
            manifest.archetype(),
            manifest.structure(),
            manifest.stack(),
            manifest.persistence(),
            List.of(),
            text,
            null,
            manifest.axizVersion(),
            manifest.extensions());
    Map<String, Object> context = generator.context(request, archetype, contract);
    Map<String, byte[]> rendered =
        JavaFormatter.formatAll(TemplateSet.load("service").render(context));

    List<String> addedClasses = added.stream().map(Names::pascal).toList();
    List<String> created = new ArrayList<>();
    List<String> proposed = new ArrayList<>();
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
              || added.stream().anyMatch(id -> path.endsWith("contract-examples/" + id + ".json"));
      boolean adapterFile =
          path.contains("/application/model/")
              || file.endsWith("Controller.java")
              || file.equals("ContractExamplesTest.java")
              || (file.endsWith("Service.java") && path.contains("/service/"))
              || (file.endsWith("Composition.java") && path.contains("/composition/"));
      if (!operationFile && !adapterFile) {
        continue;
      }
      Path target = dir.resolve(path);
      if (!Files.exists(target)) {
        Files.createDirectories(SafePaths.parentOf(target));
        Files.write(target, e.getValue());
        created.add(path);
      } else if (adapterFile && !Arrays.equals(Files.readAllBytes(target), e.getValue())) {
        Files.write(dir.resolve(path + PROPOSAL), e.getValue());
        proposed.add(path + PROPOSAL);
      }
    }
    Files.createDirectories(SafePaths.parentOf(snapshot));
    Files.writeString(snapshot, text, StandardCharsets.UTF_8);
    manifest.withOperations(ids).write(dir);
    return new Result(added, removed, created, proposed, changes, findings, true);
  }

  /** Operation ids per tag, for reports. */
  public static Map<String, List<String>> byTag(OpenApiDocument contract) {
    Map<String, List<String>> out = new LinkedHashMap<>();
    contract
        .operations()
        .forEach(op -> out.computeIfAbsent(op.tag(), t -> new ArrayList<>()).add(op.operationId()));
    return out;
  }
}
