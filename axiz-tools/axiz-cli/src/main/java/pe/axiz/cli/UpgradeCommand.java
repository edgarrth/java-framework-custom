package pe.axiz.cli;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import pe.axiz.codegen.output.SafePaths;
import pe.axiz.codegen.project.MavenReactor;
import pe.axiz.codegen.project.PomEditor;
import pe.axiz.codegen.project.ProjectLayout;
import pe.axiz.codegen.project.ProjectManifest;
import pe.axiz.codegen.project.ProjectRename;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code axiz upgrade}: stages the OpenRewrite recipes, parents and manifest in an isolated working
 * copy before applying the migration. The recipes ({@code pe.axiz:axiz-rewrite-recipes}), which
 * migrate properties, APIs and configuration.
 */
@Command(
    mixinStandardHelpOptions = true,
    name = "upgrade",
    description = "Upgrades the axiz version applying the OpenRewrite recipes.")
class UpgradeCommand extends BaseCommand implements Callable<Integer> {

  // MAJOR.MINOR.PATCH with an optional -suffix, checked in two parts so that no repetition is
  // nested inside another and the check is linear in the length of the text.
  private static final Pattern RELEASE = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");
  private static final Pattern SUFFIX = Pattern.compile("^[A-Za-z0-9.]+$");

  @Option(names = "--to", description = "Target version (default: the version of this CLI).")
  @Nullable String to;

  @Option(
      names = "--dry-run",
      description =
          "Run the migration in an isolated copy and retain its patch without changing sources.")
  boolean dryRun;

  @Option(names = "--skip-rewrite", description = "Only update the versions.")
  boolean skipRewrite;

  @Option(
      names = "--rename",
      description = "New deployment name; preserves stateful identities and Java packages.")
  @Nullable String rename;

  @Option(
      names = "--archetype",
      description = "Resolve legacy integration explicitly: core or gateway.")
  @Nullable String archetype;

  @Override
  public Integer call() throws IOException {
    Path dir = project();
    ProjectManifest manifest = ProjectManifest.read(dir, archetype);
    String target = to == null ? Version.axiz() : to;
    if (!isVersion(target)) {
      throw new IllegalArgumentException("Invalid version " + target);
    }
    List<MavenReactor.Module> modules = MavenReactor.read(dir);
    List<MavenReactor.Module> framework =
        modules.stream().filter(MavenReactor.Module::axizParent).toList();
    if (framework.isEmpty())
      throw new IllegalArgumentException("No Axiz parent in the Maven reactor");
    if (framework.stream().allMatch(module -> target.equals(module.parentVersion()))
        && target.equals(manifest.axizVersion())
        && rename == null
        && !WorkflowMigration.required(dir)
        && !CapabilityAdapters.required(dir, manifest)
        && Files.readString(dir.resolve(ProjectManifest.PATH)).contains("schema: 2")) {
      ok("Already on axiz " + target);
      return dryRun ? ExitCodes.OK : validate(dir);
    }
    out().println("Upgrading to axiz " + target);
    try (WorkingProject isolated = WorkingProject.create(dir)) {
      Path working = isolated.directory();
      // The recipe needs schema 2 selectors, while Maven must still parse the valid 1.x model.
      // Both changes are staged in isolation; the original project is untouched until success.
      manifest.write(working);
      if (rename != null) ProjectRename.apply(working, manifest, rename, true);
      if (skipRewrite) {
        warn("Recipes skipped explicitly; migration validation is still required");
      } else {
        Path wrapper = working.resolve(File.separatorChar == '\\' ? "mvnw.cmd" : "mvnw");
        String maven = Files.isRegularFile(wrapper) ? wrapper.toString() : "mvn";
        if (maven.equals("mvn") && !environment.processes().available("mvn")) {
          warn(
              "Maven not found: run the recipes later with "
                  + String.join(" ", command("mvn", target)));
          return ExitCodes.PROBLEMS;
        }
        int code = environment.processes().run(working, command(maven, target));
        if (code != 0) {
          isolated.review();
          fail(
              "OpenRewrite exited with "
                  + code
                  + "; the original project was not changed; review target/rewrite/rewrite.patch");
          return ExitCodes.PROBLEMS;
        }
      }
      Path deploymentPom = ProjectLayout.read(working, manifest).pom();
      for (MavenReactor.Module module : MavenReactor.read(working)) {
        if (module.axizParent() || (rename != null && module.pom().equals(deploymentPom))) {
          String text = Files.readString(module.pom(), StandardCharsets.UTF_8);
          if (module.axizParent()) text = PomEditor.setParentVersion(text, target);
          if (rename != null && module.pom().equals(deploymentPom))
            text = PomEditor.setArtifactId(text, rename);
          SafePaths.write(
              working,
              working.relativize(module.pom()).toString(),
              text.getBytes(StandardCharsets.UTF_8),
              true);
        }
      }
      if (rename != null) {
        ProjectRename.apply(working, manifest, rename, false);
        manifest = manifest.withName(rename);
      }
      CapabilityAdapters.apply(working, manifest);
      ProjectManifest upgraded = manifest.withAxizVersion(target);
      upgraded.write(working);
      boolean workflowsReady =
          WorkflowMigration.migrate(working, ProjectManifest.read(dir, archetype), upgraded);
      isolated.review();
      if (!workflowsReady) {
        fail(
            "Customized legacy workflows require review; merge target/rewrite/rewrite.patch. "
                + "The original project was not changed");
        return ExitCodes.PROBLEMS;
      }
      int validation = validate(working);
      if (validation != ExitCodes.OK) return validation;
      if (dryRun) {
        ok("Dry run done: see target/rewrite/rewrite.patch; the original project was not changed");
        return ExitCodes.OK;
      }
      isolated.apply();
      ok("Framework parents and .axiz/project.yaml set to " + target);
      return ExitCodes.OK;
    }
  }

  private int validate(Path dir) throws IOException {
    DoctorCommand doctor = new DoctorCommand();
    doctor.environment(environment);
    List<DoctorCommand.Check> checks = doctor.checks(dir);
    for (var check : checks) {
      if (check.level() == DoctorCommand.Level.ERROR) fail(check.message());
      else if (check.level() == DoctorCommand.Level.WARN) warn(check.message());
    }
    if (checks.stream().anyMatch(c -> c.level() == DoctorCommand.Level.ERROR))
      return ExitCodes.PROBLEMS;
    return ExitCodes.OK;
  }

  static boolean isVersion(String text) {
    int dash = text.indexOf('-');
    String release = dash < 0 ? text : text.substring(0, dash);
    return RELEASE.matcher(release).matches()
        && (dash < 0 || SUFFIX.matcher(text.substring(dash + 1)).matches());
  }

  List<String> command(String maven, String target) {
    List<String> c = new ArrayList<>();
    c.add(maven);
    c.add("-B");
    c.add("-U");
    c.add("org.openrewrite.maven:rewrite-maven-plugin:" + Version.rewritePlugin() + ":run");
    c.add("-Drewrite.recipeArtifactCoordinates=pe.axiz:axiz-rewrite-recipes:" + target);
    c.add("-Drewrite.activeRecipes=pe.axiz.UpgradeToAxiz");
    c.add("-Drewrite.exportDatatables=false");
    return c;
  }
}
