package pe.axiz.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import pe.axiz.codegen.ContractSync;
import pe.axiz.codegen.model.Capability;
import pe.axiz.codegen.model.Catalog;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.openapi.ApiOperation;
import pe.axiz.codegen.openapi.LintFinding;
import pe.axiz.codegen.openapi.OpenApiDocument;
import pe.axiz.codegen.openapi.OpenApiLinter;
import pe.axiz.codegen.project.PomEditor;
import pe.axiz.codegen.project.ProjectManifest;
import picocli.CommandLine.Command;

/**
 * {@code axiz doctor}: versions, project coherence, contract, configuration and anti-patterns. Exit
 * code 1 when an error is found (usable in CI).
 */
@Command(
    mixinStandardHelpOptions = true,
    name = "doctor",
    description = "Checks tools, versions, contract, configuration and anti-patterns.")
class DoctorCommand extends BaseCommand implements Callable<Integer> {

  /** Result of one check. */
  enum Level {
    OK,
    WARN,
    ERROR
  }

  /**
   * One finding.
   *
   * @param level severity
   * @param message what was found
   */
  record Check(Level level, String message) {}

  private static final Pattern SECRET =
      Pattern.compile(
          "(?i)^\\s*[\\w.-]*(password|secret|token|api-?key|private-key)[\\w.-]*\\s*:\\s*(.+)$");
  private static final List<AntiPattern> ANTI_PATTERNS =
      List.of(
          new AntiPattern(
              Pattern.compile("System\\.(out|err)\\.print"),
              "use SLF4J instead of System.out/err",
              false),
          new AntiPattern(
              Pattern.compile("@Autowired\\s+(private|protected)?\\s*\\w"),
              "field injection: use constructor injection",
              false),
          new AntiPattern(
              Pattern.compile("new RestTemplate\\("),
              "RestTemplate: use the declarative clients of the http-client capability",
              false),
          new AntiPattern(
              Pattern.compile("\\.block\\(\\)"), "blocking call in reactive code", true),
          new AntiPattern(
              Pattern.compile("Thread\\.sleep\\("), "Thread.sleep in production code", false),
          new AntiPattern(
              Pattern.compile("printStackTrace\\(\\)"),
              "printStackTrace: log the exception",
              false));

  record AntiPattern(Pattern pattern, String message, boolean reactiveOnly) {}

  @Override
  public Integer call() throws IOException {
    List<Check> checks = checks(project());
    for (Check c : checks) {
      switch (c.level()) {
        case OK -> ok(c.message());
        case WARN -> warn(c.message());
        case ERROR -> fail(c.message());
      }
    }
    long errors = checks.stream().filter(c -> c.level() == Level.ERROR).count();
    long warnings = checks.stream().filter(c -> c.level() == Level.WARN).count();
    out().println();
    out().println(errors + " errors, " + warnings + " warnings");
    return errors > 0 ? ExitCodes.PROBLEMS : ExitCodes.OK;
  }

  List<Check> checks(Path dir) throws IOException {
    List<Check> out = new ArrayList<>();
    int java = Runtime.version().feature();
    out.add(
        java >= 25
            ? new Check(Level.OK, "Java " + java)
            : new Check(Level.ERROR, "Java " + java + ": axiz needs Java 25"));
    for (String tool : List.of("mvn", "docker")) {
      out.add(
          environment.processes().available(tool)
              ? new Check(Level.OK, tool + " found")
              : new Check(Level.WARN, tool + " not found on the PATH"));
    }
    ProjectManifest manifest;
    try {
      manifest = ProjectManifest.read(dir);
    } catch (IOException | RuntimeException e) {
      out.add(new Check(Level.ERROR, "Invalid project: " + e.getMessage()));
      return out;
    }
    out.add(
        new Check(
            Level.OK,
            "Project "
                + manifest.name()
                + " ("
                + manifest.archetype()
                + " · "
                + manifest.framework().id()
                + " · "
                + manifest.stack().id()
                + " · axiz "
                + manifest.axizVersion()
                + ")"));

    String pom = Files.readString(dir.resolve("pom.xml"), StandardCharsets.UTF_8);
    String parent = PomEditor.parentVersion(pom);
    if (!manifest.axizVersion().equals(parent)) {
      out.add(
          new Check(
              Level.ERROR,
              "pom parent version "
                  + parent
                  + " differs from the manifest ("
                  + manifest.axizVersion()
                  + "): use axiz upgrade"));
    }
    if (!Version.axiz().equals(manifest.axizVersion())) {
      out.add(
          new Check(
              Level.WARN,
              "axiz " + Version.axiz() + " is available: axiz upgrade --to " + Version.axiz()));
    }

    Catalog catalog = Catalog.load();
    for (String name : manifest.capabilities()) {
      Capability c = catalog.capability(name).orElse(null);
      if (c == null) {
        out.add(new Check(Level.WARN, "Unknown capability " + name + " in the manifest"));
      } else if (!PomEditor.hasDependency(pom, "pe.axiz", c.starter())) {
        out.add(
            new Check(
                Level.ERROR,
                "Capability "
                    + name
                    + " is in the manifest but "
                    + c.starter()
                    + " is not in the pom: axiz add "
                    + name));
      }
    }

    if (manifest.openapi() != null) {
      Path contract = dir.resolve(manifest.openapi());
      if (!Files.isRegularFile(contract)) {
        out.add(new Check(Level.ERROR, "Contract " + manifest.openapi() + " not found"));
      } else {
        OpenApiDocument doc =
            OpenApiDocument.parse(Files.readString(contract, StandardCharsets.UTF_8));
        List<LintFinding> findings = OpenApiLinter.lint(doc);
        long errors = findings.stream().filter(LintFinding::error).count();
        out.add(
            errors > 0
                ? new Check(
                    Level.ERROR, "Contract has " + errors + " lint errors: axiz contract lint")
                : new Check(Level.OK, "Contract lint: " + findings.size() + " warnings"));
        List<String> ids = doc.operations().stream().map(ApiOperation::operationId).toList();
        if (!ids.equals(manifest.operations())
            && !new java.util.HashSet<>(ids)
                .equals(new java.util.HashSet<>(manifest.operations()))) {
          out.add(new Check(Level.WARN, "Contract and code differ: axiz contract sync"));
        }
      }
    }

    try (Stream<Path> files = Files.walk(dir.resolve("src"))) {
      List<Path> all = files.filter(Files::isRegularFile).toList();
      for (Path p : all) {
        String rel = dir.relativize(p).toString();
        if (rel.endsWith(ContractSync.PROPOSAL)) {
          out.add(new Check(Level.WARN, "Pending proposal " + rel + ": merge it and delete it"));
        }
        if (rel.startsWith("src/main/resources")
            && (rel.endsWith(".yaml") || rel.endsWith(".yml") || rel.endsWith(".properties"))) {
          secrets(p, rel, out);
        }
        if (rel.startsWith("src/main/java") && rel.endsWith(".java")) {
          antiPatterns(p, rel, manifest.stack() == Stack.REACTIVE, out);
        }
      }
      if (manifest.isService()
          && all.stream()
              .noneMatch(p -> p.getFileName().toString().equals("ArchitectureTest.java"))) {
        out.add(new Check(Level.WARN, "No ArchitectureTest: the style rules are not enforced"));
      }
    }

    Path adr = dir.resolve("docs");
    if (Files.isDirectory(adr)) {
      try (Stream<Path> files = Files.list(adr)) {
        for (Path p :
            files
                .filter(
                    f ->
                        f.toString().endsWith(".md")
                            && !f.getFileName().toString().equals("template.md"))
                .sorted()
                .toList()) {
          if (Files.readString(p, StandardCharsets.UTF_8)
              .lines()
              .anyMatch(l -> l.toLowerCase(Locale.ROOT).matches("- status: proposed.*"))) {
            out.add(new Check(Level.WARN, "architecture contract still proposed: " + dir.relativize(p)));
          }
        }
      }
    }
    return out;
  }

  private static void secrets(Path file, String rel, List<Check> out) throws IOException {
    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    for (int i = 0; i < lines.size(); i++) {
      Matcher m = SECRET.matcher(lines.get(i));
      if (m.matches()) {
        String value = m.group(2).strip();
        boolean placeholder =
            value.isEmpty()
                || value.startsWith("${")
                || value.startsWith("#")
                || value.startsWith("|")
                || value.endsWith("-ref")
                || value.matches("[a-z0-9-]+") && !value.matches(".*\\d{4,}.*");
        if (!placeholder) {
          out.add(
              new Check(
                  Level.ERROR,
                  "Possible secret in "
                      + rel
                      + ":"
                      + (i + 1)
                      + " (use the secret store, never config)"));
        }
      }
    }
  }

  private static void antiPatterns(Path file, String rel, boolean reactive, List<Check> out)
      throws IOException {
    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i).strip();
      if (line.startsWith("//") || line.startsWith("*")) {
        continue;
      }
      for (AntiPattern a : ANTI_PATTERNS) {
        if ((!a.reactiveOnly() || reactive) && a.pattern().matcher(line).find()) {
          out.add(new Check(Level.WARN, rel + ":" + (i + 1) + " " + a.message()));
        }
      }
    }
  }
}
