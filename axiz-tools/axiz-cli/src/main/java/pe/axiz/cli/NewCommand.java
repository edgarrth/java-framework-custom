package pe.axiz.cli;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import pe.axiz.codegen.GenerationException;
import pe.axiz.codegen.Names;
import pe.axiz.codegen.ProjectGenerator;
import pe.axiz.codegen.RequestValidator;
import pe.axiz.codegen.model.Archetype;
import pe.axiz.codegen.model.Framework;
import pe.axiz.codegen.model.ProjectRequest;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.openapi.LintFinding;
import pe.axiz.codegen.output.GeneratedProject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code axiz new}: creates a service (or a library or starter) from an archetype and its contract,
 * offline with the embedded templates or online through the initializr.
 */
@Command(
    mixinStandardHelpOptions = true,
    name = "new",
    description = "Creates a project from an archetype (interactive with -i).",
    footer = {
      "",
      "Examples:",
      "  axiz new payments-business --archetype business --openapi api.yaml",
      "  axiz new notifications-processor --archetype event-processor -c inbox -c messaging-sns",
      "  axiz new -i"
    })
class NewCommand extends BaseCommand implements Callable<Integer> {

  @Parameters(
      index = "0",
      arity = "0..1",
      description = "Project name in kebab-case, e.g. payments-business.")
  @Nullable String name;

  @Option(
      names = {"-a", "--archetype"},
      description = "Archetype (see axiz catalog).")
  @Nullable String archetype;

  @Option(
      names = {"-f", "--framework"},
      description = "Style: layered, hexagonal, cqrs, eda, bff, batch.")
  @Nullable String framework;

  @Option(
      names = {"-s", "--stack"},
      description = "servlet or reactive.")
  @Nullable String stack;

  @Option(
      names = {"-p", "--persistence"},
      description = "none, aurora or dynamodb (aurora is JPA on servlet, R2DBC on reactive).")
  @Nullable String persistence;

  @Option(
      names = {"-g", "--group"},
      description = "Maven group (default pe.axiz.<domain>).")
  @Nullable String group;

  @Option(names = "--package", description = "Base package (default pe.axiz.<domain>.<service>).")
  @Nullable String basePackage;

  @Option(names = "--description", description = "One-line description.")
  @Nullable String description;

  @Option(
      names = {"-c", "--capability"},
      description = "Extra capability (repeatable).")
  List<String> capabilities = new ArrayList<>();

  @Option(
      names = "--openapi",
      description = "OpenAPI 3.1 contract (required by archetypes exposing REST).")
  @Nullable Path openapi;

  @Option(names = "--asyncapi", description = "AsyncAPI contract of the events (optional).")
  @Nullable Path asyncapi;

  @Option(
      names = {"-o", "--output"},
      defaultValue = ".",
      description = "Directory where the project folder is created.")
  Path output = Path.of(".");

  @Option(names = "--server", description = "initializr URL (online mode); token from AXIZ_TOKEN.")
  @Nullable URI server;

  @Option(
      names = "--axiz-version",
      description = "Framework version (default: the one of this CLI).")
  @Nullable String axizVersion;

  @Option(
      names = {"-i", "--interactive"},
      description = "Asks for every missing value.")
  boolean interactive;

  @Option(names = "--force", description = "Overwrite files of an existing folder.")
  boolean force;

  @Override
  public Integer call() throws IOException {
    ProjectGenerator generator = ProjectGenerator.withDefaults();
    Prompter prompt = new Prompter(environment.input(), out(), interactive);
    String projectName = prompt.ask("Project name (kebab-case)", name, null);
    Archetype a =
        generator
            .catalog()
            .archetype(
                prompt.choose(
                    "Archetype",
                    archetype,
                    generator.catalog().archetypes().stream().map(Archetype::name).toList(),
                    "business"))
            .orElseThrow(() -> new IllegalArgumentException("Unknown archetype; see axiz catalog"));
    Framework f =
        Framework.of(
            prompt.choose(
                "Framework",
                framework,
                a.frameworks().stream().map(Framework::id).toList(),
                a.defaultFramework().id()));
    Stack st =
        Stack.of(
            prompt.choose(
                "Stack",
                stack,
                a.stacks().stream().map(Stack::id).toList(),
                a.defaultStack().id()));
    String p =
        a.persistence().isEmpty()
            ? "none"
            : prompt.choose("Persistence", persistence, a.persistence(), a.defaultPersistence());
    String domain =
        Names.words(projectName).isEmpty()
            ? "app"
            : Names.packageSegment(Names.words(projectName).get(0));
    String g = prompt.ask("Maven group", group, "pe.axiz." + domain);
    String service =
        Names.packageSegment(
            projectName.contains("-")
                ? projectName.substring(projectName.indexOf('-') + 1)
                : projectName);
    String pkg =
        prompt.ask("Base package", basePackage, g.endsWith("." + service) ? g : g + "." + service);
    String desc = prompt.ask("Description", description, a.role());
    if (interactive && capabilities.isEmpty()) {
      String extra = prompt.ask("Extra capabilities (comma separated, see axiz catalog)", null, "");
      for (String c : extra.split(",")) {
        if (!c.isBlank()) {
          capabilities.add(c.strip());
        }
      }
    }
    Path contract = openapi;
    if (a.rest() && contract == null) {
      String answer = prompt.ask("OpenAPI 3.1 contract file", null, null);
      contract = Path.of(answer);
    }
    String openApiText = contract == null ? null : read(contract);
    String asyncText = asyncapi == null ? null : read(asyncapi);

    ProjectRequest request =
        new ProjectRequest(
            projectName,
            g,
            pkg,
            desc,
            a.name(),
            f,
            st,
            p,
            capabilities,
            openApiText,
            asyncText,
            axizVersion == null ? Version.axiz() : axizVersion);
    Path target = output.toAbsolutePath().normalize().resolve(projectName);
    if (Files.exists(target) && !force) {
      try (var entries = Files.list(target)) {
        if (entries.findAny().isPresent()) {
          throw new IllegalArgumentException(
              target + " already exists and is not empty (use --force)");
        }
      }
    }
    if (server != null) {
      byte[] zip =
          new InitializrClient(server, environment.env().get("AXIZ_TOKEN")).generate(json(request));
      Files.createDirectories(target);
      ZipExtractor.extract(zip, target, force);
    } else {
      RequestValidator.Result result = generator.validate(request);
      report(result.findings());
      if (!result.valid()) {
        result.problems().forEach(this::fail);
        return ExitCodes.PROBLEMS;
      }
      GeneratedProject project;
      try {
        project = generator.generate(request);
      } catch (GenerationException e) {
        e.problems().forEach(this::fail);
        return ExitCodes.PROBLEMS;
      }
      project.writeTo(target, force);
      if (!pe.axiz.codegen.render.JavaFormatter.available()) {
        warn("Generated without google-java-format: run mvn spotless:apply once");
      }
    }
    ok("Created " + a.name() + " / " + f.id() + " / " + st.id() + " / " + p + " in " + target);
    out().println();
    out().println("Next steps:");
    out().println("  cd " + projectName);
    out().println("  docker compose up -d && mvn verify");
    out().println("  axiz doctor");
    return ExitCodes.OK;
  }

  private void report(List<LintFinding> findings) {
    for (LintFinding finding : findings) {
      String line = finding.rule() + " " + finding.location() + ": " + finding.message();
      if (finding.error()) {
        fail(line);
      } else {
        warn(line);
      }
    }
  }

  private static String read(Path file) throws IOException {
    if (Files.size(file) > pe.axiz.codegen.openapi.OpenApiDocument.MAX_SIZE) {
      throw new IllegalArgumentException(
          file + " exceeds " + pe.axiz.codegen.openapi.OpenApiDocument.MAX_SIZE + " bytes");
    }
    return Files.readString(file, StandardCharsets.UTF_8);
  }

  static Map<String, Object> json(ProjectRequest r) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", r.name());
    m.put("groupId", r.groupId());
    m.put("basePackage", r.basePackage());
    m.put("description", r.description());
    m.put("archetype", r.archetype());
    m.put("framework", r.framework().id());
    m.put("stack", r.stack().id());
    m.put("persistence", r.persistence());
    m.put("capabilities", r.capabilities());
    m.put("openApi", r.openApi());
    m.put("asyncApi", r.asyncApi());
    m.put("frameworkVersion", r.frameworkVersion());
    return m;
  }

  /** Reads answers in interactive mode; otherwise uses the given value or the default. */
  static final class Prompter {
    private final java.io.BufferedReader in;
    private final java.io.PrintWriter out;
    private final boolean interactive;

    Prompter(java.io.BufferedReader in, java.io.PrintWriter out, boolean interactive) {
      this.in = in;
      this.out = out;
      this.interactive = interactive;
    }

    String ask(String question, @Nullable String given, @Nullable String fallback)
        throws IOException {
      if (given != null) {
        return given;
      }
      if (!interactive) {
        if (fallback != null) {
          return fallback;
        }
        throw new IllegalArgumentException(question + " is required (or use -i)");
      }
      out.print(
          question + (fallback == null || fallback.isEmpty() ? "" : " [" + fallback + "]") + ": ");
      out.flush();
      String line = in.readLine();
      if (line == null || line.isBlank()) {
        if (fallback == null) {
          throw new IllegalArgumentException(question + " is required");
        }
        return fallback;
      }
      return line.strip();
    }

    String choose(String question, @Nullable String given, List<String> options, String fallback)
        throws IOException {
      String value =
          ask(question + " (" + String.join("/", options) + ")", given, fallback)
              .toLowerCase(Locale.ROOT);
      if (!options.contains(value)) {
        throw new IllegalArgumentException(question + " must be one of " + options);
      }
      return value;
    }
  }
}
