package pe.axiz.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import pe.axiz.codegen.project.PomEditor;
import pe.axiz.codegen.project.ProjectManifest;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code axiz upgrade}: moves the project to another framework version: parent and manifest, then
 * the OpenRewrite recipes of that version ({@code pe.axiz:axiz-rewrite-recipes}), which migrate
 * properties, APIs and configuration.
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
      description = "Show what would change (rewrite:dryRun), change nothing.")
  boolean dryRun;

  @Option(names = "--skip-rewrite", description = "Only update the versions.")
  boolean skipRewrite;

  @Override
  public Integer call() throws IOException {
    Path dir = project();
    ProjectManifest manifest = ProjectManifest.read(dir);
    String target = to == null ? Version.axiz() : to;
    if (!isVersion(target)) {
      throw new IllegalArgumentException("Invalid version " + target);
    }
    Path pom = dir.resolve("pom.xml");
    String text = Files.readString(pom, StandardCharsets.UTF_8);
    String current = PomEditor.parentVersion(text);
    if (target.equals(current) && target.equals(manifest.axizVersion())) {
      ok("Already on axiz " + target);
      return ExitCodes.OK;
    }
    out().println("Upgrading from " + current + " to " + target);
    if (!dryRun) {
      Files.writeString(pom, PomEditor.setParentVersion(text, target), StandardCharsets.UTF_8);
      manifest.withAxizVersion(target).write(dir);
      ok("pom.xml parent and .axiz/project.yaml set to " + target);
    }
    if (skipRewrite) {
      return ExitCodes.OK;
    }
    String maven = Files.isRegularFile(dir.resolve("mvnw")) ? "./mvnw" : "mvn";
    if (maven.equals("mvn") && !environment.processes().available("mvn")) {
      warn(
          "Maven not found: run the recipes later with "
              + String.join(" ", command("mvn", target)));
      return ExitCodes.OK;
    }
    int code = environment.processes().run(dir, command(maven, target));
    if (code != 0) {
      fail("OpenRewrite exited with " + code + "; review the output, the version change is kept");
      return ExitCodes.PROBLEMS;
    }
    ok(
        dryRun
            ? "Dry run done: see target/rewrite/rewrite.patch"
            : "Recipes applied: review the diff and run mvn verify");
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
    c.add("org.openrewrite.maven:rewrite-maven-plugin:" + (dryRun ? "dryRun" : "run"));
    c.add("-Drewrite.recipeArtifactCoordinates=pe.axiz:axiz-rewrite-recipes:" + target);
    c.add("-Drewrite.activeRecipes=pe.axiz.UpgradeToAxiz");
    c.add("-Drewrite.exportDatatables=false");
    return c;
  }
}
