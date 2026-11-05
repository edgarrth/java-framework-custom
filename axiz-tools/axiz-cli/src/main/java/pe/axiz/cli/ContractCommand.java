package pe.axiz.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import pe.axiz.codegen.AsyncContractSync;
import pe.axiz.codegen.ContractSync;
import pe.axiz.codegen.ProjectGenerator;
import pe.axiz.codegen.asyncapi.AsyncApiDiff;
import pe.axiz.codegen.asyncapi.AsyncApiDocument;
import pe.axiz.codegen.openapi.ContractDiff;
import pe.axiz.codegen.openapi.LintFinding;
import pe.axiz.codegen.openapi.OpenApiDocument;
import pe.axiz.codegen.openapi.OpenApiLinter;
import pe.axiz.codegen.openapi.SafeYaml;
import pe.axiz.codegen.output.SafePaths;
import pe.axiz.codegen.project.ProjectManifest;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** {@code axiz contract sync|lint|diff}. */
@Command(
    mixinStandardHelpOptions = true,
    name = "contract",
    description = "Works with the OpenAPI and AsyncAPI contracts of the service.",
    subcommands = {
      ContractCommand.Sync.class,
      ContractCommand.Lint.class,
      ContractCommand.Diff.class
    })
class ContractCommand implements Runnable {

  @Spec CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().usage(spec.commandLine().getOut());
  }

  static void print(BaseCommand c, List<LintFinding> findings) {
    for (LintFinding f : findings) {
      String line = f.rule() + " " + f.location() + ": " + f.message();
      if (f.error()) {
        c.fail(line);
      } else {
        c.warn(line);
      }
    }
  }

  static void print(BaseCommand c, List<ContractDiff.Change> changes, boolean onlyBreaking) {
    for (ContractDiff.Change change : changes) {
      if (change.breaking()) {
        c.fail("BREAKING " + change.location() + ": " + change.message());
      } else if (!onlyBreaking) {
        c.ok(change.location() + ": " + change.message());
      }
    }
  }

  enum Type {
    openapi,
    asyncapi
  }

  static boolean async(Path directory, @Nullable Type type) throws IOException {
    if (type != null) return type == Type.asyncapi;
    ProjectManifest manifest = ProjectManifest.read(directory);
    return manifest.openapi() == null && manifest.asyncapi() != null;
  }

  /** Regenerates from the updated contract. */
  @Command(
      mixinStandardHelpOptions = true,
      name = "sync",
      description =
          "Generates new operations, proposes updated controllers and checks compatibility.")
  static class Sync extends BaseCommand implements Callable<Integer> {

    @Option(
        names = "--allow-breaking",
        description = "Apply even if existing consumers break (new major version).")
    boolean allowBreaking;

    @Option(
        names = "--type",
        description = "Contract: openapi or asyncapi (default: OpenAPI when present).")
    @Nullable Type type;

    @Override
    public Integer call() throws IOException {
      if (async(project(), type)) {
        if (allowBreaking)
          throw new IllegalArgumentException(
              "AsyncAPI requires a new .vN type; --allow-breaking cannot replace a published type");
        var result = new AsyncContractSync().sync(project());
        print(this, result.changes(), false);
        if (!result.applied()) {
          fail("Preserve the published .vN type and introduce .vN+1 in parallel");
          return ExitCodes.PROBLEMS;
        }
        result.created().forEach(path -> ok("Created " + path));
        result.proposed().forEach(path -> warn("Review and merge " + path));
        if (!result.created().isEmpty() || !result.proposed().isEmpty()) formatSources(project());
        ok("AsyncAPI payloads and generation snapshot synchronized");
        return ExitCodes.OK;
      }
      ContractSync.Result r =
          new ContractSync(ProjectGenerator.withDefaults()).sync(project(), allowBreaking);
      print(this, r.findings());
      print(this, r.changes(), false);
      if (!r.applied()) {
        fail(
            r.findings().stream().anyMatch(LintFinding::error)
                ? "The contract has lint errors: nothing generated"
                : "Breaking changes for existing consumers: publish a new version (/v2) or use"
                    + " --allow-breaking");
        return ExitCodes.PROBLEMS;
      }
      r.created().forEach(f -> ok("Created " + f));
      if (!r.created().isEmpty() || !r.proposed().isEmpty()) {
        formatSources(project());
      }
      r.proposed().forEach(f -> warn("Review and merge " + f + " (then delete it)"));
      r.removed().forEach(id -> warn("Operation " + id + " left the contract: remove its code"));
      if (r.added().isEmpty() && r.removed().isEmpty()) {
        ok("Code is in sync with the contract");
      }
      return ExitCodes.OK;
    }
  }

  /** Lints a contract with the corporate rules. */
  @Command(
      mixinStandardHelpOptions = true,
      name = "lint",
      description = "Checks the contract against the axiz rules (same as the Spectral ruleset).")
  static class Lint extends BaseCommand implements Callable<Integer> {

    @Parameters(arity = "0..1", description = "Contract file (default: the project's).")
    @Nullable Path file;

    @Option(names = "--type", description = "Contract: openapi or asyncapi.")
    @Nullable Type type;

    @Override
    public Integer call() throws IOException {
      Path contract = file;
      if (contract == null) {
        ProjectManifest manifest = ProjectManifest.read(project());
        String path = async(project(), type) ? manifest.asyncapi() : manifest.openapi();
        if (path == null)
          throw new IllegalArgumentException(
              "The project has no "
                  + (type == Type.asyncapi ? "AsyncAPI" : "OpenAPI")
                  + " contract");
        contract = SafePaths.target(project(), path);
      }
      String text = Files.readString(contract, StandardCharsets.UTF_8);
      boolean isAsync =
          type == Type.asyncapi
              || (type == null
                  && SafeYaml.create(2 * 1024 * 1024).load(text) instanceof Map<?, ?> root
                  && root.containsKey("asyncapi"));
      if (isAsync) {
        var document = AsyncApiDocument.parse(text);
        ok(
            "AsyncAPI structure: "
                + document.published().size()
                + " published types, "
                + document.consumed().size()
                + " consumed types; corporate Spectral rules run in CI");
        return ExitCodes.OK;
      }
      List<LintFinding> findings = OpenApiLinter.lint(OpenApiDocument.parse(text));
      print(this, findings);
      long errors = findings.stream().filter(LintFinding::error).count();
      if (findings.isEmpty()) {
        ok("No findings");
      }
      return errors > 0 ? ExitCodes.PROBLEMS : ExitCodes.OK;
    }
  }

  /** Compares two versions of a contract. */
  @Command(
      mixinStandardHelpOptions = true,
      name = "diff",
      description = "Reports changes between two contract versions; exit 1 when breaking.")
  static class Diff extends BaseCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Previous contract.")
    Path previous = Path.of("");

    @Parameters(index = "1", description = "New contract.")
    Path current = Path.of("");

    @Option(names = "--type", description = "Contract: openapi or asyncapi (default: openapi).")
    Type type = Type.openapi;

    @Override
    public Integer call() throws IOException {
      List<ContractDiff.Change> changes =
          type == Type.asyncapi
              ? AsyncApiDiff.compare(
                  AsyncApiDocument.parse(Files.readString(previous)),
                  AsyncApiDocument.parse(Files.readString(current)))
              : ContractDiff.compare(
                  OpenApiDocument.parse(Files.readString(previous, StandardCharsets.UTF_8)),
                  OpenApiDocument.parse(Files.readString(current, StandardCharsets.UTF_8)));
      print(this, changes, false);
      if (changes.isEmpty()) {
        ok("No changes");
      }
      return ContractDiff.hasBreaking(changes) ? ExitCodes.PROBLEMS : ExitCodes.OK;
    }
  }
}
