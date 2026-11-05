package pe.axiz.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import pe.axiz.adapter.outbox.jdbc.JdbcOutboxPreflight;
import pe.axiz.codegen.AsyncContractSync;
import pe.axiz.codegen.ContractSync;
import pe.axiz.codegen.HttpClientGenerator;
import pe.axiz.codegen.RequestValidator;
import pe.axiz.codegen.asyncapi.AsyncApiDocument;
import pe.axiz.codegen.asyncapi.AsyncApiSubscriptions;
import pe.axiz.codegen.model.Capability;
import pe.axiz.codegen.model.Catalog;
import pe.axiz.codegen.model.ProjectRequest;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.openapi.ApiOperation;
import pe.axiz.codegen.openapi.LintFinding;
import pe.axiz.codegen.openapi.OpenApiDocument;
import pe.axiz.codegen.openapi.OpenApiLinter;
import pe.axiz.codegen.openapi.SafeYaml;
import pe.axiz.codegen.output.SafePaths;
import pe.axiz.codegen.project.MavenReactor;
import pe.axiz.codegen.project.PomEditor;
import pe.axiz.codegen.project.ProjectLayout;
import pe.axiz.codegen.project.ProjectManifest;
import pe.axiz.codegen.project.ProjectRename;
import pe.axiz.core.commons.json.JsonWriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code axiz doctor}: versions, project coherence, contract, configuration and anti-patterns. Exit
 * code 1 when an error is found (usable in CI).
 */
@Command(
    mixinStandardHelpOptions = true,
    name = "doctor",
    description = "Checks tools, versions, contract, configuration and anti-patterns.")
class DoctorCommand extends BaseCommand implements Callable<Integer> {
  @Option(
      names = "--outbox-jdbc-url",
      description =
          "Read-only outbox upgrade report (PostgreSQL); credentials from"
              + " AXIZ_DOCTOR_DB_USER/PASSWORD.")
  @Nullable String outboxJdbcUrl;

  @Option(
      names = "--outbox-table",
      defaultValue = "axiz_outbox",
      description = "Outbox table for the optional upgrade report.")
  String outboxTable = "axiz_outbox";

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

  private static final Pattern EXTEND_WITH =
      Pattern.compile(
          "@(?:org\\.junit\\.jupiter\\.api\\.extension\\.)?ExtendWith\\s*\\(([^)]*)\\)");
  private static final Pattern BLOCKHOUND_TYPE =
      Pattern.compile("(?:^|[\\s.,{])BlockHoundExtension\\s*\\.\\s*class\\b");

  private static final Pattern SECRET =
      Pattern.compile(
          "(?i)^\\s*([\\w.-]*(?:password|secret|token|api-?key|private-key)[\\w.-]*)\\s*:\\s*(.+)$");
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
              Pattern.compile("\\.(?:block|blockFirst|blockLast)\\s*\\("),
              "blocking call in reactive code",
              true),
          new AntiPattern(
              Pattern.compile("Schedulers\\.(?:boundedElastic|newBoundedElastic)\\s*\\("),
              "reactive I/O must use native asynchronous ports",
              true),
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
      Consumer<String> report =
          switch (c.level()) {
            case OK -> this::ok;
            case WARN -> this::warn;
            case ERROR -> this::fail;
          };
      report.accept(c.message());
    }
    long errors = checks.stream().filter(c -> c.level() == Level.ERROR).count();
    long warnings = checks.stream().filter(c -> c.level() == Level.WARN).count();
    if (outboxJdbcUrl != null) {
      long failures = outboxPreflight(outboxJdbcUrl);
      if (failures < 0) errors++;
      else warnings += failures;
    }
    out().println();
    out().println(errors + " errors, " + warnings + " warnings");
    return errors > 0 ? ExitCodes.PROBLEMS : ExitCodes.OK;
  }

  private long outboxPreflight(String url) {
    if (!url.startsWith("jdbc:postgresql://")
        || url.contains("@")
        || Pattern.compile("(?i)(?:[?&])(?:user|password|sslpassword)=").matcher(url).find()) {
      fail(
          "Outbox report requires a PostgreSQL JDBC URL without credentials; use"
              + " AXIZ_DOCTOR_DB_USER/PASSWORD");
      return -1;
    }
    var properties = new Properties();
    properties.setProperty("connectTimeout", "5");
    properties.setProperty("socketTimeout", "15");
    for (String key : List.of("user", "password")) {
      String value =
          environment.env().get("AXIZ_DOCTOR_DB_" + (key.equals("user") ? "USER" : "PASSWORD"));
      if (value != null) properties.setProperty(key, value);
    }
    try (var connection = DriverManager.getConnection(url, properties)) {
      connection.setReadOnly(true);
      connection.setAutoCommit(false);
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      long count =
          JdbcOutboxPreflight.report(
              connection,
              outboxTable,
              failure -> {
                Map<String, @Nullable Object> record = new LinkedHashMap<>();
                record.put("id", failure.id());
                record.put("aggregateType", failure.aggregateType());
                record.put("aggregateId", failure.aggregateId());
                record.put("destination", failure.destination());
                record.put("attempts", failure.attempts());
                record.put("lastError", failure.error());
                record.put("publishedSuccessors", failure.publishedSuccessors());
                record.put("pendingSuccessors", failure.pendingSuccessors());
                warn("FAILED outbox: " + JsonWriter.write(record));
              });
      connection.commit();
      if (count == 0) ok("Outbox preflight: no FAILED rows");
      else
        warn(
            "Outbox preflight: "
                + count
                + " FAILED rows retained; review retry/skip before upgrading; "
                + "no events were changed");
      return count;
    } catch (SQLException | IllegalArgumentException failure) {
      fail(
          "Outbox preflight failed ("
              + failure.getClass().getSimpleName()
              + "); verify connectivity, permissions and table name");
      return -1;
    }
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
                + " / "
                + manifest.structure().id()
                + " / "
                + manifest.stack().id()
                + " / axiz "
                + manifest.axizVersion()
                + ")"));

    ProjectRename.problems(dir).forEach(problem -> out.add(new Check(Level.ERROR, problem)));

    List<MavenReactor.Module> modules;
    try {
      modules = MavenReactor.read(dir);
    } catch (IOException | IllegalArgumentException problem) {
      out.add(new Check(Level.ERROR, "Invalid Maven reactor: " + problem.getMessage()));
      return out;
    }
    ProjectLayout layout;
    try {
      layout = ProjectLayout.read(dir, manifest);
    } catch (IOException | IllegalArgumentException problem) {
      out.add(new Check(Level.ERROR, "Invalid service module: " + problem.getMessage()));
      return out;
    }
    List<String> reactorPoms = new ArrayList<>();
    for (MavenReactor.Module module : modules)
      reactorPoms.add(Files.readString(module.pom(), StandardCharsets.UTF_8));
    if (modules.stream().noneMatch(MavenReactor.Module::axizParent)) {
      out.add(new Check(Level.ERROR, "No Axiz parent in the Maven reactor"));
    }
    for (MavenReactor.Module module : modules) {
      if (module.axizParent() && !manifest.axizVersion().equals(module.parentVersion())) {
        out.add(
            new Check(
                Level.ERROR,
                "pom parent version "
                    + module.parentVersion()
                    + " differs from the manifest ("
                    + manifest.axizVersion()
                    + ") in "
                    + dir.toAbsolutePath().normalize().relativize(module.pom())
                    + ": use axiz upgrade"));
      }
    }
    if (!Version.axiz().equals(manifest.axizVersion())) {
      out.add(
          new Check(
              Level.WARN,
              "axiz " + Version.axiz() + " is available: axiz upgrade --to " + Version.axiz()));
    }

    Catalog catalog = Catalog.load();
    String api =
        manifest.openapi() == null || !Files.isRegularFile(dir.resolve(manifest.openapi()))
            ? null
            : Files.readString(dir.resolve(manifest.openapi()));
    String asyncPath = manifest.asyncapi();
    String asyncApi =
        asyncPath == null || !Files.isRegularFile(SafePaths.target(dir, asyncPath))
            ? null
            : Files.readString(SafePaths.target(dir, asyncPath));
    var validation =
        new RequestValidator(catalog)
            .validate(
                new ProjectRequest(
                    manifest.name(),
                    manifest.groupId(),
                    manifest.basePackage(),
                    "",
                    manifest.archetype(),
                    manifest.structure(),
                    manifest.stack(),
                    manifest.persistence(),
                    manifest.capabilities(),
                    api,
                    asyncApi,
                    manifest.axizVersion(),
                    manifest.extensions()));
    validation.problems().forEach(problem -> out.add(new Check(Level.ERROR, problem)));
    Path clients = dir.resolve(".axiz/clients");
    if (Files.isDirectory(clients)) {
      try (Stream<Path> contracts = Files.list(clients)) {
        for (Path contract : contracts.filter(Files::isRegularFile).toList()) {
          try {
            HttpClientGenerator.generate(manifest, "validation", Files.readString(contract));
          } catch (IllegalArgumentException problem) {
            out.add(new Check(Level.ERROR, contract.getFileName() + ": " + problem.getMessage()));
          }
        }
      }
    }
    for (String name : manifest.capabilities()) {
      Capability c = catalog.capability(name).orElse(null);
      if (c == null) {
        out.add(new Check(Level.WARN, "Unknown capability " + name + " in the manifest"));
      } else if (reactorPoms.stream()
          .noneMatch(pom -> PomEditor.hasDependency(pom, "pe.axiz", c.starter()))) {
        out.add(
            new Check(
                Level.ERROR,
                "Capability "
                    + name
                    + " is in the manifest but "
                    + c.starter()
                    + " is not in the Maven reactor poms: axiz add "
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
            && !new HashSet<>(ids).equals(new HashSet<>(manifest.operations()))) {
          out.add(new Check(Level.WARN, "Contract and code differ: axiz contract sync"));
        }
      }
    }

    if (manifest.asyncapi() != null) {
      try {
        Path contract = SafePaths.target(dir, manifest.asyncapi());
        var document = AsyncApiDocument.parse(Files.readString(contract));
        Path contractModule = layout.module();
        var subscriptionProblems = AsyncApiSubscriptions.problems(contractModule, document);
        subscriptionProblems.forEach(problem -> out.add(new Check(Level.ERROR, problem)));
        if (subscriptionProblems.isEmpty())
          out.add(new Check(Level.OK, "AsyncAPI subscriptions match the consumed types"));
        Path snapshot = SafePaths.target(dir, AsyncContractSync.SNAPSHOT);
        if (!Files.exists(snapshot)
            || !SafeYaml.create(2 * 1024 * 1024)
                .load(Files.readString(snapshot))
                .equals(SafeYaml.create(2 * 1024 * 1024).load(Files.readString(contract))))
          out.add(
              new Check(
                  Level.ERROR,
                  "AsyncAPI generation snapshot differs: axiz contract sync --type asyncapi"));
      } catch (IOException | RuntimeException problem) {
        out.add(
            new Check(
                Level.ERROR,
                "Invalid AsyncAPI contract or configuration: " + problem.getMessage()));
      }
    }

    for (MavenReactor.Module module : modules) {
      Path moduleDirectory = SafePaths.parentOf(module.pom());
      Path sourceDirectory = SafePaths.target(moduleDirectory, "src");
      if (!Files.isDirectory(sourceDirectory)) continue;
      try (Stream<Path> files = Files.walk(sourceDirectory)) {
        List<Path> all = files.filter(Files::isRegularFile).toList();
        for (Path p : all) {
          String rel = moduleDirectory.relativize(p).toString();
          SafePaths.target(moduleDirectory, rel);
          if (rel.endsWith(ContractSync.PROPOSAL)) {
            out.add(new Check(Level.WARN, "Pending proposal " + rel + ": merge it and delete it"));
          }
          if (rel.startsWith("src/main/resources")
              && (rel.endsWith(".yaml") || rel.endsWith(".yml") || rel.endsWith(".properties"))) {
            secrets(p, rel, out);
          }
          if (rel.startsWith("src/main/java") && rel.endsWith(".java")) {
            antiPatterns(p, rel, manifest.stack() == Stack.REACTIVE, out);
            architectureImports(p, rel, manifest, out);
          }
        }
        if (manifest.stack() == Stack.REACTIVE && moduleDirectory.equals(layout.module())) {
          boolean instrumented = false;
          Path testSources = moduleDirectory.resolve("src/test/java");
          for (Path test : all) {
            if (!test.startsWith(testSources) || !test.toString().endsWith(".java")) continue;
            Matcher extensions = EXTEND_WITH.matcher(JavaSource.code(Files.readString(test)));
            while (extensions.find()) {
              if (BLOCKHOUND_TYPE.matcher(extensions.group(1)).find()) instrumented = true;
            }
          }
          if (!instrumented)
            out.add(
                new Check(
                    Level.ERROR,
                    "Reactive tests must declare @ExtendWith(BlockHoundExtension.class)"));
        }
        if (manifest.isService()
            && moduleDirectory.equals(layout.module())
            && all.stream().noneMatch(p -> SafePaths.fileName(p).equals("ArchitectureTest.java"))) {
          out.add(new Check(Level.WARN, "No ArchitectureTest: the style rules are not enforced"));
        }
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
                            && !SafePaths.fileName(f).equals("template.md"))
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
        String key = m.group(1).toLowerCase(Locale.ROOT);
        String scalar = value.replaceFirst("\\s+#.*$", "").strip();
        if (scalar.length() >= 2
            && ((scalar.startsWith("\"") && scalar.endsWith("\""))
                || (scalar.startsWith("'") && scalar.endsWith("'"))))
          scalar = scalar.substring(1, scalar.length() - 1);
        boolean metadata =
            ((key.equals("api-key-header") || key.endsWith(".api-key-header"))
                    && scalar.matches("[A-Za-z0-9!#$%&'*+.^_`|~-]+"))
                || ((key.equals("password-mode") || key.endsWith(".password-mode"))
                    && Set.of("TEXT", "LEGACY_SHA1_DIGEST").contains(scalar));
        boolean placeholder =
            metadata
                || value.isEmpty()
                || value.startsWith("${")
                || value.startsWith("#")
                || value.startsWith("|")
                || value.endsWith("-ref")
                || (value.matches("[a-z0-9-]+") && !value.matches(".*\\d{4,}.*"));
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
          out.add(
              new Check(
                  a.reactiveOnly() ? Level.ERROR : Level.WARN,
                  rel + ":" + (i + 1) + " " + a.message()));
        }
      }
    }
  }

  private static void architectureImports(
      Path file, String rel, ProjectManifest manifest, List<Check> out) throws IOException {
    boolean persistence = rel.contains("/repository/") || rel.contains("/adapter/out/persistence/");
    boolean domain = manifest.structure().id().equals("hexagonal") && rel.contains("/domain/");
    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i).strip();
      if (!line.startsWith("import ")) continue;
      String imported = line.substring(7).replaceFirst("^static\\s+", "").strip();
      String problem = null;
      if (imported.startsWith("pe.axiz.kit.") && !persistence) {
        problem = "kits are only allowed in repository or adapter.out.persistence packages";
      } else if (imported.startsWith("pe.axiz.adapter.")
          || imported.startsWith("pe.axiz.core.persistence.")) {
        problem = "use framework ports and Spring facades instead of importing technology adapters";
      } else if (domain
          && !(imported.startsWith("java.")
              || imported.startsWith("org.jspecify.annotations.")
              || imported.startsWith(manifest.basePackage() + ".domain.")
              || List.of(
                      "commons",
                      "domain",
                      "money",
                      "errors",
                      "id",
                      "identifiers",
                      "sensitive",
                      "time")
                  .stream()
                  .anyMatch(
                      capability -> imported.startsWith("pe.axiz.core." + capability + ".")))) {
        problem = "hexagonal domain must depend only on Java and permitted Axiz domain contracts";
      }
      if (problem != null) out.add(new Check(Level.ERROR, rel + ":" + (i + 1) + " " + problem));
    }
  }
}
