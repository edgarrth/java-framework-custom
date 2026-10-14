package pe.axiz.codegen;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import pe.axiz.codegen.model.Framework;
import pe.axiz.codegen.project.ProjectManifest;
import pe.axiz.codegen.render.JavaFormatter;
import pe.axiz.codegen.render.TemplateSet;

/**
 * {@code axiz generate <type> <name>}: code coherent with the project's style, stack and
 * persistence. Returns new files only; files that already exist are reported by the caller and
 * never overwritten.
 */
public final class ArtifactGenerator {

  /** Artifact types and the styles they apply to (empty: every service style). */
  public static final Map<String, Set<Framework>> TYPES;

  static {
    Map<String, Set<Framework>> t = new LinkedHashMap<>();
    t.put("usecase", Set.of(Framework.HEXAGONAL));
    t.put("command", Set.of(Framework.CQRS));
    t.put("query", Set.of(Framework.CQRS));
    t.put("handler", Set.of());
    t.put("event-handler", Set.of());
    t.put("event", Set.of());
    t.put("entity", Set.of());
    t.put("repository", Set.of());
    t.put(
        "controller",
        Set.of(Framework.HEXAGONAL, Framework.CQRS, Framework.LAYERED, Framework.BFF));
    t.put("dto", Set.of(Framework.HEXAGONAL, Framework.CQRS, Framework.LAYERED, Framework.BFF));
    t.put("mapper", Set.of(Framework.HEXAGONAL, Framework.CQRS, Framework.LAYERED, Framework.BFF));
    t.put("migration", Set.of());
    t.put("error-code", Set.of());
    t.put("test", Set.of());
    TYPES = java.util.Collections.unmodifiableMap(t);
  }

  /**
   * Options of a generation.
   *
   * @param category error category for {@code error-code} (e.g. {@code UNPROCESSABLE})
   * @param title title for {@code error-code}
   * @param source source type of a {@code mapper}
   * @param target target type of a {@code mapper}
   */
  public record Options(
      @Nullable String category,
      @Nullable String title,
      @Nullable String source,
      @Nullable String target) {
    public static Options none() {
      return new Options(null, null, null, null);
    }
  }

  /**
   * Files of an artifact.
   *
   * @param created new files (never overwrite: the caller skips existing ones)
   * @param updated existing files rewritten with the artifact added (error codes)
   * @param appended text appended to existing files (message bundles)
   * @param dependencies artifacts ({@code groupId:artifactId}) the pom must declare
   * @param notes follow-up hints for the developer
   */
  public record Result(
      Map<String, byte[]> created,
      Map<String, byte[]> updated,
      Map<String, String> appended,
      List<String> dependencies,
      List<String> notes) {}

  private final Clock clock;

  public ArtifactGenerator(Clock clock) {
    this.clock = clock;
  }

  /**
   * Generates the files of an artifact.
   *
   * @param existingErrors source of the errors class, when it exists (error codes are appended)
   * @throws IllegalArgumentException for an unknown type, a type the style does not use or an
   *     invalid name
   */
  public Result generate(
      ProjectManifest project,
      String type,
      String name,
      Options options,
      @Nullable String existingErrors) {
    return generate(project, type, name, options, existingErrors, null);
  }

  /**
   * Generates the files of an artifact.
   *
   * @param existingErrors source of the errors class, when it exists (error codes are appended)
   * @param migrationVersion Flyway version of a generated migration ({@code Migrations.next}); a
   *     UTC timestamp when null
   */
  public Result generate(
      ProjectManifest project,
      String type,
      String name,
      Options options,
      @Nullable String existingErrors,
      @Nullable String migrationVersion) {
    Set<Framework> styles = TYPES.get(type);
    if (styles == null) {
      throw new IllegalArgumentException(
          "Unknown type " + type + "; available: " + String.join(", ", TYPES.keySet()));
    }
    if (!project.isService()) {
      throw new IllegalArgumentException(
          "axiz generate works on services; " + project.archetype() + " projects are plain code");
    }
    if (!styles.isEmpty() && !styles.contains(project.framework())) {
      throw new IllegalArgumentException(
          type + " does not apply to the " + project.framework().id() + " style");
    }
    if (Names.pascal(name).isEmpty()) {
      throw new IllegalArgumentException("Invalid name " + name);
    }
    boolean aurora = "aurora".equals(project.persistence());
    boolean dynamodb = "dynamodb".equals(project.persistence());
    if ((type.equals("entity") || type.equals("repository")) && !aurora && !dynamodb) {
      throw new IllegalArgumentException(
          type + " needs persistence (aurora or dynamodb); the project has none");
    }
    if (type.equals("migration") && !aurora) {
      throw new IllegalArgumentException("Flyway migrations need Aurora persistence");
    }
    if (type.equals("error-code") && options.category() != null) {
      if (!ERROR_CATEGORIES.contains(options.category().toUpperCase(Locale.ROOT))) {
        throw new IllegalArgumentException(
            "Unknown error category " + options.category() + "; use one of " + ERROR_CATEGORIES);
      }
    }
    Map<String, Object> c = context(project, type, name, options, existingErrors, migrationVersion);
    String kind = type.equals("event-handler") ? "handler" : type;
    Map<String, Object> scoped = new LinkedHashMap<>(c);
    scoped.put("type", Map.of(kind.replace("-", ""), true));
    TemplateSet set = TemplateSet.load("artifacts");
    Map<String, byte[]> files = JavaFormatter.formatAll(set.render(scoped));
    List<String> dependencies =
        type.equals("mapper") ? List.of("org.mapstruct:mapstruct") : List.of();
    List<String> notes = new java.util.ArrayList<>();
    if (type.equals("handler") || type.equals("event-handler")) {
      if (project.capabilities().stream().noneMatch(cap -> cap.startsWith("messaging-"))) {
        notes.add(
            "Consuming events needs a messaging capability: axiz add messaging-kafka (or"
                + " messaging-sqs)");
      }
      notes.add(
          "Subscribe the service to the topic or queue (axiz.messaging.kafka.topics /"
              + " axiz.messaging.sqs.queues)");
    }
    if (type.equals("entity") && "aurora".equals(project.persistence())) {
      notes.add("Review the generated Flyway migration before running the service");
    }
    if (type.equals("controller")) {
      notes.add(
          "Declare the endpoints in the OpenAPI contract too: the contract is the source of truth");
    }
    if (!type.equals("error-code")) {
      return new Result(files, Map.of(), Map.of(), dependencies, notes);
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> a = (Map<String, Object>) c.get("artifact");
    Map<String, String> appended = new LinkedHashMap<>();
    String line = "error." + a.get("code") + "=" + a.get("title") + "\n";
    appended.put("src/main/resources/messages.properties", line);
    appended.put("src/main/resources/messages_en.properties", line);
    if (existingErrors == null) {
      return new Result(files, Map.of(), appended, List.of(), List.of());
    }
    String constant = set.renderTemplate("errors/constant.java.mustache", scoped);
    int end = existingErrors.lastIndexOf('}');
    if (end < 0) {
      throw new IllegalArgumentException("Cannot update the errors class: no closing brace");
    }
    String updated = existingErrors.substring(0, end).stripTrailing() + "\n\n" + constant + "}\n";
    Map<String, byte[]> out = new LinkedHashMap<>();
    files
        .keySet()
        .forEach(
            path ->
                out.put(
                    path,
                    JavaFormatter.format(updated)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    return new Result(Map.of(), out, appended, List.of(), List.of());
  }

  /** Categories accepted by {@code error-code} (see {@code ErrorCategory}). */
  public static final List<String> ERROR_CATEGORIES =
      List.of(
          "BAD_REQUEST",
          "UNAUTHORIZED",
          "FORBIDDEN",
          "NOT_FOUND",
          "CONFLICT",
          "PRECONDITION_FAILED",
          "UNPROCESSABLE",
          "TOO_MANY_REQUESTS",
          "INTERNAL",
          "BAD_GATEWAY",
          "SERVICE_UNAVAILABLE",
          "GATEWAY_TIMEOUT");

  Map<String, Object> context(
      ProjectManifest p,
      String type,
      String name,
      Options options,
      @Nullable String existingErrors,
      @Nullable String migrationVersion) {
    Map<String, Object> c = new LinkedHashMap<>();
    Map<String, Object> project = new LinkedHashMap<>();
    project.put("name", p.name());
    project.put("basePackage", p.basePackage());
    project.put("packagePath", Names.packagePath(p.basePackage()));
    project.put("className", Names.pascal(p.name()));
    project.put("groupId", p.groupId());
    project.put("errorPrefix", Names.errorPrefix(p.groupId(), p.name()));
    c.put("project", project);

    Map<String, Object> fw = new LinkedHashMap<>();
    for (Framework f : Framework.values()) {
      fw.put(f.id(), f == p.framework());
    }
    String rest =
        switch (p.framework()) {
          case HEXAGONAL, CQRS -> "adapter.in.rest";
          default -> "controller";
        };
    fw.put("restPackage", rest);
    fw.put(
        "eventPackage",
        switch (p.framework()) {
          case HEXAGONAL, CQRS -> "domain.event";
          default -> "event";
        });
    fw.put(
        "handlerPackage",
        switch (p.framework()) {
          case HEXAGONAL, CQRS -> "adapter.in.messaging";
          default -> "handler";
        });
    fw.put(
        "entityPackage",
        switch (p.framework()) {
          case HEXAGONAL, CQRS -> "adapter.out.persistence";
          case LAYERED -> "model";
          default -> "persistence";
        });
    fw.put(
        "repositoryPackage",
        switch (p.framework()) {
          case HEXAGONAL, CQRS -> "adapter.out.persistence";
          case LAYERED -> "repository";
          default -> "persistence";
        });
    fw.put(
        "errorsPackage",
        switch (p.framework()) {
          case HEXAGONAL, CQRS -> "domain";
          case LAYERED -> "service";
          default -> "";
        });
    c.put("fw", paths(fw));
    c.put(
        "stack",
        Map.of(
            "servlet",
            p.stack().id().equals("servlet"),
            "reactive",
            p.stack().id().equals("reactive")));
    c.put(
        "persistence",
        Map.of(
            "aurora",
            "aurora".equals(p.persistence()),
            "dynamodb",
            "dynamodb".equals(p.persistence())));

    String simple = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
    String subPackage =
        name.contains(".") ? name.substring(0, name.lastIndexOf('.')).toLowerCase(Locale.ROOT) : "";
    Map<String, Object> a = new LinkedHashMap<>();
    String className = Names.pascal(simple);
    a.put("name", simple);
    a.put("className", className);
    a.put("camel", Names.camel(simple));
    a.put("kebab", Names.kebab(simple));
    a.put("snake", Names.snake(simple));
    a.put(
        "plural",
        Names.kebab(simple).endsWith("s") ? Names.kebab(simple) : Names.kebab(simple) + "s");
    a.put("table", Names.snake(simple));
    a.put("eventType", p.groupId() + "." + Names.kebab(simple).replace('-', '.') + ".v1");
    a.put(
        "version",
        migrationVersion != null
            ? migrationVersion
            : DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                .withZone(ZoneOffset.UTC)
                .format(clock.instant()));
    a.put("subPackage", subPackage);
    a.put("hasSubPackage", !subPackage.isEmpty());
    a.put("subPath", subPackage.replace('.', '/'));
    a.put("source", typeName(options.source()));
    a.put("target", typeName(options.target()));
    String target = typeName(options.target());
    a.put("targetSimple", target.substring(target.lastIndexOf('.') + 1));
    a.put("hasMapping", options.source() != null && options.target() != null);
    String category =
        options.category() == null ? "UNPROCESSABLE" : options.category().toUpperCase(Locale.ROOT);
    a.put("category", category);
    a.put(
        "title",
        options.title() == null
            ? String.join(" ", Names.words(simple))
            : options.title().replace("\"", "'"));
    a.put("constant", Names.snake(simple).toUpperCase(Locale.ROOT));
    a.put(
        "code",
        p.groupId().isEmpty()
            ? ""
            : Names.errorPrefix(p.groupId(), p.name())
                + "-"
                + String.format(Locale.ROOT, "%04d", nextErrorNumber(existingErrors)));
    a.put("errorsClass", Names.pascal(p.name()) + "Errors");
    a.put("errorsExists", existingErrors != null);
    c.put("artifact", a);
    return c;
  }

  /** A type given on the command line: qualified names are kept, simple names become PascalCase. */
  static String typeName(@Nullable String type) {
    if (type == null || type.isBlank()) {
      return "";
    }
    String t = type.strip();
    return t.contains(".") ? t : Names.pascal(t);
  }

  private static Map<String, Object> paths(Map<String, Object> fw) {
    Map<String, Object> out = new LinkedHashMap<>(fw);
    fw.forEach(
        (k, v) -> {
          if (k.endsWith("Package") && v instanceof String s) {
            out.put(k.replace("Package", "Path"), s.replace('.', '/'));
            out.put(k.replace("Package", "Suffix"), s.isEmpty() ? "" : "." + s);
            out.put(k.replace("Package", "Dir"), s.isEmpty() ? "" : "/" + s.replace('.', '/'));
          }
        });
    return out;
  }

  /** Next free number of the service error codes ({@code -NNNN}). */
  static int nextErrorNumber(@Nullable String existingErrors) {
    if (existingErrors == null) {
      return 1;
    }
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("-(\\d{4})\"").matcher(existingErrors);
    int max = 0;
    while (m.find()) {
      max = Math.max(max, Integer.parseInt(m.group(1)));
    }
    return max + 1;
  }

  /** Types available for a style. */
  public static List<String> typesFor(Framework framework) {
    return TYPES.entrySet().stream()
        .filter(e -> e.getValue().isEmpty() || e.getValue().contains(framework))
        .map(Map.Entry::getKey)
        .toList();
  }
}
