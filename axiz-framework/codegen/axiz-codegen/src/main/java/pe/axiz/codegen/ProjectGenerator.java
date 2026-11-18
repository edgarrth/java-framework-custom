package pe.axiz.codegen;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import pe.axiz.codegen.asyncapi.AsyncApiDocument;
import pe.axiz.codegen.model.Archetype;
import pe.axiz.codegen.model.ArchitectureScopes;
import pe.axiz.codegen.model.Capability;
import pe.axiz.codegen.model.Catalog;
import pe.axiz.codegen.model.ProjectRequest;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.model.Structure;
import pe.axiz.codegen.model.Style;
import pe.axiz.codegen.openapi.ApiOperation;
import pe.axiz.codegen.openapi.OpenApiDocument;
import pe.axiz.codegen.output.GeneratedProject;
import pe.axiz.codegen.render.JavaFormatter;
import pe.axiz.codegen.render.TemplateSet;
import pe.axiz.codegen.render.YamlProperties;

/**
 * Generates a complete project (service, library or starter) from a request: validation, context
 * and templates. Used identically by the initializr and the CLI.
 */
public final class ProjectGenerator {

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "Catalog owns defensive snapshots and exposes no mutator; SpotBugs does not infer "
              + "its immutability.")
  private final Catalog catalog;

  private final RequestValidator validator;

  public ProjectGenerator(Catalog catalog) {
    this.catalog = catalog;
    this.validator = new RequestValidator(catalog);
  }

  public static ProjectGenerator withDefaults() {
    return new ProjectGenerator(Catalog.load());
  }

  public Catalog catalog() {
    return catalog;
  }

  public RequestValidator.Result validate(ProjectRequest request) {
    return validator.validate(request);
  }

  /**
   * Generates the project.
   *
   * @throws GenerationException with every problem when the request is not valid
   */
  public GeneratedProject generate(ProjectRequest request) {
    RequestValidator.Result result = validator.validate(request);
    Archetype archetype = result.archetype();
    if (!result.valid() || archetype == null) {
      throw new GenerationException(result.problems(), result.findings());
    }
    Map<String, Object> context = context(request, archetype, result.contract());
    String set = archetype.isService() ? "service" : archetype.name();
    Map<String, byte[]> files = new LinkedHashMap<>(TemplateSet.load(set).render(context));
    String asyncApi = request.asyncApi();
    if (archetype.isService() && asyncApi != null && !asyncApi.isBlank()) {
      files.putAll(
          EventPayloads.generate(
              request.basePackage(),
              request.groupId(),
              request.structure(),
              AsyncApiDocument.parse(asyncApi)));
      files.putAll(
          EventAdapters.generate(
              request.basePackage(),
              request.groupId(),
              request.structure(),
              request.stack(),
              AsyncApiDocument.parse(asyncApi)));
    }
    return new GeneratedProject(request.name(), JavaFormatter.formatAll(files));
  }

  /** Template context; also used by the artifact generators of the CLI. */
  public Map<String, Object> context(
      ProjectRequest r, Archetype a, @Nullable OpenApiDocument contract) {
    Map<String, Object> c = new LinkedHashMap<>();
    Map<String, Object> project = new LinkedHashMap<>();
    project.put("name", r.name());
    project.put("artifactId", r.name());
    project.put("groupId", r.groupId());
    project.put("basePackage", r.basePackage());
    project.put("packagePath", Names.packagePath(r.basePackage()));
    String description = r.description().isBlank() ? a.role() : r.description().strip();
    // descriptions land in Markdown, Javadoc, XML (content and comments) and YAML: each format
    // gets its own escaping, so ordinary punctuation never breaks a generated file
    String text =
        description.endsWith(".")
            ? description.substring(0, description.length() - 1)
            : description;
    project.put("description", text);
    project.put("descriptionJavadoc", javadoc(text));
    project.put("descriptionXml", xmlText(text));
    project.put("descriptionXmlComment", xmlComment(text));
    project.put("descriptionYaml", "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
    project.put("className", Names.pascal(r.name()));
    project.put("camelName", Names.camel(r.name()));
    project.put("errorPrefix", Names.errorPrefix(r.groupId(), r.name()));
    project.put(
        "title", String.join(" ", Names.words(r.name()).stream().map(Names::pascal).toList()));
    project.put("databaseName", Names.snake(r.name()));
    c.put("project", project);
    Map<String, Object> platform = new LinkedHashMap<>();
    String core = r.name().startsWith("axiz-") ? r.name().substring(5) : r.name();
    if (r.name().startsWith("axiz-core-")) core = core.substring(5);
    String capabilityArtifact = r.name().startsWith("axiz-") ? "axiz-" + core : r.name();
    platform.put("capabilityArtifact", capabilityArtifact);
    platform.put("coreArtifact", r.name().startsWith("axiz-") ? "axiz-core-" + core : r.name());
    platform.put("prefix", r.name().startsWith("axiz-") ? "axiz." + core : core);
    String springPackage =
        r.basePackage().contains(".core.")
            ? r.basePackage().replaceFirst("\\.core\\.", ".spring.")
            : r.basePackage() + ".spring";
    platform.put("springPackage", springPackage);
    platform.put("springPackagePath", Names.packagePath(springPackage));
    platform.put("propertiesClass", Names.pascal(core) + "Properties");
    platform.put("autoConfigurationClass", Names.pascal(core) + "AutoConfiguration");
    platform.put("mainClass", Names.pascal(core));
    platform.put("mainCamel", Names.camel(core));
    c.put("platform", platform);
    c.put("axizVersion", r.frameworkVersion());

    Map<String, Object> archetype = new LinkedHashMap<>();
    archetype.put("name", a.name());
    archetype.put("role", a.role());
    archetype.put("rest", a.rest());
    archetype.put("cronjob", "cronjob".equals(a.deployment()));
    archetype.put("deployment", "deployment".equals(a.deployment()));
    archetype.put("process", r.capabilities().contains("saga"));
    archetype.put("enum", a.name().replace("-", "_").toUpperCase(Locale.ROOT));
    archetype.put(a.name().replace("-", ""), true);
    c.put("archetype", archetype);

    String suppliedAsyncApi = r.asyncApi();
    Map<String, Object> fw = new LinkedHashMap<>();
    fw.put("id", r.structure().id());
    fw.put(
        "exampleEvents",
        r.structure() == Structure.EDA && (suppliedAsyncApi == null || suppliedAsyncApi.isBlank()));
    for (Structure f : Structure.values()) {
      fw.put(f.id(), f == r.structure());
    }
    fw.put("cqrs", r.cqrs());
    fw.put("hexagonal", r.structure() == Structure.HEXAGONAL && !r.cqrs());
    fw.put("starter", r.structure().starter());
    fw.put("enum", r.structure().name());
    String restPackage =
        switch (r.structure()) {
          case HEXAGONAL -> "adapter.in.rest";
          default -> "controller";
        };
    fw.put("restPackage", restPackage);
    fw.put("restPath", restPackage.replace('.', '/'));
    fw.put("packages", packages(r.structure()));
    c.put(
        "extensions",
        r.extensions().stream().map(e -> Map.of("id", e.id(), "enum", e.name())).toList());
    c.put("hasExtensions", !r.extensions().isEmpty());
    fw.put("hasApplicationModels", r.structure() == Structure.HEXAGONAL);
    fw.put("usecases", r.structure() == Structure.HEXAGONAL && !r.cqrs());
    fw.put("handlers", r.cqrs());
    c.put("fw", fw);

    Map<String, Object> stack = new LinkedHashMap<>();
    stack.put("id", r.stack().id());
    stack.put("servlet", r.stack().id().equals("servlet"));
    stack.put("reactive", r.stack().id().equals("reactive"));
    c.put("stack", stack);

    String store = Catalog.store(r.persistence(), r.stack());
    Map<String, Object> persistence = new LinkedHashMap<>();
    persistence.put("id", r.persistence());
    persistence.put("store", store);
    persistence.put("aurora", "aurora".equals(r.persistence()));
    persistence.put("jpa", Catalog.AURORA.equals(store));
    persistence.put("r2dbc", Catalog.R2DBC.equals(store));
    persistence.put("dynamodb", "dynamodb".equals(r.persistence()));
    persistence.put("none", "none".equals(r.persistence()));
    c.put("persistence", persistence);
    // reactive styles get non-blocking handlers unless the store only has a blocking client
    fw.put("reactiveHandlers", r.stack() == Stack.REACTIVE);

    List<Capability> resolved = catalog.resolve(capabilityNames(catalog, r, a), store);
    Map<String, Object> caps = new LinkedHashMap<>();
    List<Map<String, Object>> capabilityRows = new ArrayList<>();
    Set<String> starterIds = new LinkedHashSet<>();
    Set<String> dependencyIds = new LinkedHashSet<>();
    Set<String> configMapKeys = new LinkedHashSet<>();
    Set<String> secrets = new LinkedHashSet<>();
    for (Capability cap : resolved) {
      caps.put(cap.name().replace("-", ""), true);
      capabilityRows.add(
          Map.of("name", cap.name(), "starter", cap.starter(), "description", cap.description()));
      starterIds.add(cap.starter());
      dependencyIds.addAll(cap.dependencies(store));
      configMapKeys.addAll(cap.configMapKeys());
      secrets.addAll(cap.secrets());
    }
    List<Map<String, Object>> starters = new ArrayList<>();
    starterIds.forEach(id -> starters.add(Map.of("artifactId", id)));
    if (r.structure() != Structure.NONE) {
      starters.add(Map.of("artifactId", r.structure().starter()));
    }
    r.extensions().forEach(e -> starters.add(Map.of("artifactId", e.starter())));
    List<Map<String, Object>> dependencies = new ArrayList<>();
    for (String d : dependencyIds) {
      String[] ga = d.split(":", -1);
      dependencies.add(
          Map.of(
              "groupId", ga[0], "artifactId", ga[1], "runtime", ga[1].startsWith("axiz-adapter-")));
    }
    ArchitectureScopes scopes = ArchitectureScopes.of(resolved);
    c.put("tokenizationScope", scopes.tokenization());
    c.put("cardDataScope", scopes.cardData());
    c.put("caps", caps);
    c.put("schema", schema(caps));
    c.put("capabilities", capabilityRows);
    c.put("starters", starters);
    c.put("dependencies", dependencies);
    c.put("infra", infrastructure(resolved, r));
    c.put("auditResources", auditResources(resolved));
    c.put("config", configuration(r, a, resolved, configMapKeys, secrets));

    Map<String, Object> api = new LinkedHashMap<>();
    api.put("present", contract != null);
    String apiModelPackage =
        r.basePackage()
            + switch (r.structure()) {
              case HEXAGONAL -> ".adapter.in.rest.model";
              case LAYERED -> ".model.api";
              case BFF -> ".composition.model";
              case EDA -> ".event.model";
              case BATCH -> ".job.model";
              case NONE -> ".api.model";
            };
    api.put("modelPackage", apiModelPackage);
    String openApi = r.openApi();
    api.put("text", openApi == null ? "" : openApi);
    api.put("title", contract == null ? "" : contract.title());
    api.put("version", contract == null ? "" : contract.version());
    String asyncApi = r.asyncApi();
    api.put("asyncapi", asyncApi == null ? "" : asyncApi);
    api.put("hasAsyncapi", asyncApi != null && !asyncApi.isBlank());
    c.put("api", api);

    List<Map<String, Object>> operations = new ArrayList<>();
    Map<String, List<Map<String, Object>>> byTag = new LinkedHashMap<>();
    if (contract != null) {
      for (ApiOperation op : contract.operations()) {
        if (!op.scopes().isEmpty()
            && Boolean.TRUE.equals(caps.get("security"))
            && op.requestSchema() != null
            && op.requestExample() == null)
          throw new IllegalArgumentException(
              "Secured operation "
                  + op.operationId()
                  + " requires a request example for its generated denial test");
        Map<String, Object> o = operation(op, r.basePackage());
        o.put("modelPackage", apiModelPackage);
        if (r.cqrs()) {
          o.put("modelPackage", r.basePackage() + ".application.model");
        }
        operations.add(o);
        byTag.computeIfAbsent(op.tag(), t -> new ArrayList<>()).add(o);
      }
    }
    if ((r.structure() == Structure.HEXAGONAL) && contract != null) {
      c.put("applicationModels", ApplicationModels.records(contract));
    }
    c.put("operations", operations);
    c.put(
        "hasProtectedOperations",
        operations.stream().anyMatch(op -> !Boolean.TRUE.equals(op.get("publicEndpoint"))));
    List<Map<String, Object>> tags = new ArrayList<>();
    byTag.forEach(
        (tag, ops) -> {
          Map<String, Object> t = new LinkedHashMap<>();
          t.put("name", tag);
          t.put("className", Names.pascal(tag));
          t.put("operations", ops);
          var secured =
              ops.stream()
                  .filter(op -> !String.valueOf(op.get("authorizationExpression")).isEmpty())
                  .toList();
          t.put("securedOperations", secured);
          var authenticated =
              ops.stream()
                  .filter(op -> Boolean.TRUE.equals(op.get("authenticatedAccess")))
                  .toList();
          t.put("authenticatedOperations", authenticated);
          t.put("hasSecuredOperations", !secured.isEmpty() || !authenticated.isEmpty());
          t.put(
              "hasScopedOperations",
              ops.stream().anyMatch(op -> Boolean.TRUE.equals(op.get("requiresScopes"))));
          t.put(
              "hasAuthenticatedOperations",
              ops.stream().anyMatch(op -> Boolean.TRUE.equals(op.get("authenticatedAccess"))));
          t.put(
              "hasPublicOperations",
              ops.stream().anyMatch(op -> Boolean.TRUE.equals(op.get("publicEndpoint"))));
          t.put("usesPaging", ops.stream().anyMatch(o -> Boolean.TRUE.equals(o.get("paged"))));
          t.put("usesModels", ops.stream().anyMatch(o -> Boolean.TRUE.equals(o.get("hasModel"))));
          TreeSet<String> models = new TreeSet<>();
          for (Map<String, Object> o : ops) {
            if (o.get("models") instanceof List<?> used) {
              for (Object m : used) {
                if (m instanceof Map<?, ?> model) {
                  models.add(String.valueOf(model.get("name")));
                }
              }
            }
          }
          t.put("models", models.stream().map(m -> Map.of("name", m)).toList());
          t.put("modelPackage", apiModelPackage);
          List<String[]> fields = new ArrayList<>();
          switch (r.structure()) {
            case HEXAGONAL -> {
              if (r.cqrs()) {
                String prefix = Boolean.TRUE.equals(fw.get("reactiveHandlers")) ? "Reactive" : "";
                if (any(ops, "write"))
                  fields.add(new String[] {prefix + "CommandBus", "commandBus"});
                if (any(ops, "readOnly"))
                  fields.add(new String[] {prefix + "QueryBus", "queryBus"});
              } else {
                ops.forEach(
                    o ->
                        fields.add(
                            new String[] {
                              o.get("className") + "UseCase", o.get("methodName") + "UseCase"
                            }));
              }
            }
            case LAYERED -> fields.add(new String[] {Names.pascal(tag) + "Service", "service"});
            case BFF -> fields.add(new String[] {Names.pascal(tag) + "Composition", "composition"});
            default -> {
              // no REST adapter
            }
          }
          List<Map<String, Object>> fieldRows = new ArrayList<>();
          for (int i = 0; i < fields.size(); i++) {
            fieldRows.add(
                Map.of(
                    "type",
                    fields.get(i)[0],
                    "qualifiedType",
                    controllerDependency(r, fw, fields.get(i)[0]),
                    "name",
                    fields.get(i)[1],
                    "last",
                    i == fields.size() - 1));
          }
          t.put("fields", fieldRows);
          t.put("usesValid", ops.stream().anyMatch(o -> Boolean.TRUE.equals(o.get("hasRequest"))));
          t.put("usesList", ops.stream().anyMatch(o -> Boolean.TRUE.equals(o.get("usesList"))));
          t.put("usesPathParams", any(ops, "hasPathParams"));
          t.put("usesQuery", any(ops, "hasQueryParams"));
          t.put("usesNullable", any(ops, "optionalQuery"));
          t.put("usesNoContent", any(ops, "noContent"));
          t.put("usesCreated", any(ops, "created"));
          t.put("usesRequestBody", any(ops, "hasRequest"));
          t.put("usesGet", ops.stream().anyMatch(o -> "GET".equals(o.get("httpMethod"))));
          t.put("usesPost", ops.stream().anyMatch(o -> "POST".equals(o.get("httpMethod"))));
          t.put("usesPut", ops.stream().anyMatch(o -> "PUT".equals(o.get("httpMethod"))));
          t.put("usesPatch", ops.stream().anyMatch(o -> "PATCH".equals(o.get("httpMethod"))));
          t.put("usesDelete", ops.stream().anyMatch(o -> "DELETE".equals(o.get("httpMethod"))));
          t.put("usesCommands", ops.stream().anyMatch(o -> Boolean.TRUE.equals(o.get("write"))));
          t.put("usesQueries", any(ops, "readOnly"));
          tags.add(t);
        });
    c.put("tags", tags);
    c.put("hasOperations", !operations.isEmpty());
    List<Map<String, Object>> examples =
        operations.stream().filter(o -> Boolean.TRUE.equals(o.get("exampleTestable"))).toList();
    c.put("examples", examples);
    c.put("hasExamples", !examples.isEmpty());
    c.put(
        "exampleModels",
        examples.stream()
            .map(o -> o.get("requestType"))
            .distinct()
            .map(t -> Map.of("name", t))
            .toList());
    c.put(
        "environments",
        List.of(
            environment("dev", 1, 2, "250m", "512Mi", false),
            environment("qa", 2, 3, "250m", "512Mi", false),
            environment("prod", 3, 10, "500m", "1Gi", true)));
    return c;
  }

  private static Map<String, Object> environment(
      String name, int min, int max, String cpu, String memory, boolean prod) {
    Map<String, Object> e = new LinkedHashMap<>();
    e.put("name", name);
    e.put("minReplicas", min);
    e.put("maxReplicas", max);
    e.put("cpu", cpu);
    e.put("memory", memory);
    e.put("prod", prod);
    e.put("samplingProbability", prod ? "0.1" : "1.0");
    return e;
  }

  /** Packages of each style with their role (package-info files of the skeleton). */
  static List<Map<String, Object>> packages(Structure structure) {
    // name and role, in order; an exhaustive switch expression: a new style must declare its own
    List<String> p =
        switch (structure) {
          case HEXAGONAL ->
              List.of(
                  "domain", "Domain model: plain Java, no structure dependencies.",
                  "application.usecase", "Use cases: one class per operation, transactional.",
                  "application.model", "Owned application contracts independent of HTTP models.",
                  "application.port.out", "Outbound ports implemented by adapters.",
                  "adapter.in.rest", "REST adapter generated from the OpenAPI contract.",
                  "adapter.out", "Outbound adapters: persistence, HTTP clients, messaging.",
                  "config", "Wiring of use cases, ports and adapters.");
          case LAYERED ->
              List.of(
                  "controller", "REST controllers generated from the OpenAPI contract.",
                  "service", "Business services, transactional.",
                  "repository", "Data access, persistent entities and items.",
                  "model", "Application values and API models, independent of persistence.");
          case BFF ->
              List.of(
                  "controller", "Channel API generated from the OpenAPI contract.",
                  "composition", "Compositions of downstream calls, one per API tag.",
                  "client", "HTTP clients of downstream services.");
          case EDA ->
              List.of("handler", "Event handlers, one per event type.", "event", "Event payloads.");
          case BATCH -> List.of("job", "Jobs, steps, readers, processors and writers.");
          // platform archetypes have their own layout
          case NONE -> List.of();
        };
    List<Map<String, Object>> out = new ArrayList<>();
    for (int i = 0; i < p.size(); i += 2) {
      String name = p.get(i);
      out.add(Map.of("name", name, "path", name.replace('.', '/'), "description", p.get(i + 1)));
    }
    return out;
  }

  private static boolean any(List<Map<String, Object>> ops, String key) {
    return ops.stream().anyMatch(o -> Boolean.TRUE.equals(o.get(key)));
  }

  /** Archetype starters, persistence and the requested capabilities (before requirements). */
  static List<String> capabilityNames(Catalog catalog, ProjectRequest r, Archetype a) {
    Set<String> names = new LinkedHashSet<>();
    String store = Catalog.store(r.persistence(), r.stack());
    for (String starter : a.starters()) {
      if (catalog.capability(starter).map(c -> c.supports(store)).orElse(false)) names.add(starter);
    }
    // what the style starter brings: the service gets its tables, properties and resources too
    for (String implied : catalog.style(r.structure()).capabilities()) {
      if (catalog.capability(implied).map(c -> c.supports(store)).orElse(false)) {
        names.add(implied);
      }
    }
    if ("aurora".equals(r.persistence())) {
      // JPA on servlet; R2DBC on the reactive stack, where JDBC would block the event loop
      names.add(r.stack() == Stack.REACTIVE ? "r2dbc" : "jpa");
    }
    if ("dynamodb".equals(r.persistence())) {
      names.add("dynamodb");
    }
    names.addAll(r.capabilities());
    return List.copyOf(names);
  }

  /** XML element content: the markup characters become entities. */
  static String xmlText(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  /**
   * Inside an XML comment: no {@code --} anywhere (whatever the run of hyphens) and no hyphen right
   * before the closing {@code -->}.
   */
  static String xmlComment(String text) {
    StringBuilder b = new StringBuilder(text.length() + 4);
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '-' && b.length() > 0 && b.charAt(b.length() - 1) == '-') {
        b.append(' ');
      }
      b.append(c);
    }
    if (b.length() > 0 && b.charAt(b.length() - 1) == '-') {
      b.append(' ');
    }
    return b.toString();
  }

  /** Javadoc text: HTML-escaped (doclint) and without a comment terminator. */
  static String javadoc(String text) {
    return xmlText(text).replace("*/", "*&#47;").replace("@", "&#64;");
  }

  /**
   * Structure tables the initial migration creates: those of the chosen capabilities, plus those
   * the style starter wires on its own when the service has a JDBC database. A store that is wired
   * always has its table, so nothing fails later on the first write or poll.
   */
  static Map<String, Object> schema(Map<String, Object> caps) {
    Map<String, Object> schema = new LinkedHashMap<>();
    for (String table : Style.TABLES) {
      String capability = "shedlock".equals(table) ? "scheduling" : table;
      schema.put(table, caps.containsKey(capability));
    }
    return schema;
  }

  /** Local infrastructure the service needs (Docker Compose, Testcontainers, README). */
  static Map<String, Object> infrastructure(List<Capability> caps, ProjectRequest r) {
    Set<String> names = new LinkedHashSet<>();
    for (Capability c : caps) {
      names.add(c.name());
    }
    boolean postgres =
        names.contains("jpa") || names.contains("r2dbc") || "aurora".equals(r.persistence());
    boolean kafka = names.contains("messaging-kafka");
    boolean redis = names.stream().anyMatch(name -> name.endsWith("-redis"));
    List<String> localstack = new ArrayList<>();
    if (names.contains("dynamodb")) {
      localstack.add("dynamodb");
    }
    if (names.contains("storage-s3")) {
      localstack.add("s3");
    }
    if (names.contains("messaging-sqs") || names.contains("audit-sqs")) {
      localstack.add("sqs");
    }
    if (names.contains("messaging-sns")) {
      localstack.add("sns");
    }
    if (names.contains("crypto-kms")) {
      localstack.add("kms");
    }
    if (names.contains("secrets-aws")) {
      localstack.add("secretsmanager");
    }
    if (names.contains("secrets-aws") || names.contains("config-aws")) {
      localstack.add("ssm");
    }
    Map<String, Object> infra = new LinkedHashMap<>();
    infra.put("postgres", postgres);
    infra.put("kafka", kafka);
    infra.put("redis", redis);
    infra.put("localstack", !localstack.isEmpty());
    infra.put("localstackServices", String.join(",", localstack));
    infra.put("keycloak", names.contains("security"));
    infra.put("wiremock", names.contains("http-client") || names.contains("soap"));
    infra.put("any", postgres || kafka || redis || !localstack.isEmpty());
    List<String> configurations = new ArrayList<>();
    if (postgres) {
      configurations.add("PostgresTestConfiguration");
    }
    if (kafka) {
      configurations.add("KafkaTestConfiguration");
    }
    if (redis) {
      configurations.add("RedisTestConfiguration");
    }
    boolean auditTestResources = Boolean.TRUE.equals(auditResources(caps).get("required"));
    infra.put("sharedLocalstack", !localstack.isEmpty() && !auditTestResources);
    if (!localstack.isEmpty() && !auditTestResources) {
      configurations.add("LocalStackTestConfiguration");
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    for (int i = 0; i < configurations.size(); i++) {
      rows.add(Map.of("name", configurations.get(i), "last", i == configurations.size() - 1));
    }
    infra.put("testConfigurations", rows);
    infra.put(
        "importList",
        "{" + String.join(", ", configurations.stream().map(n -> n + ".class").toList()) + "}");
    return infra;
  }

  /** application.yaml, application-local.yaml and Helm config values. */
  static Map<String, Object> configuration(
      ProjectRequest r,
      Archetype a,
      List<Capability> caps,
      Set<String> configMapKeys,
      Set<String> secrets) {
    Map<String, Object> app = new LinkedHashMap<>();
    app.put("spring.application.name", r.name());
    app.put(
        "spring.main.web-application-type",
        r.stack() == Stack.REACTIVE ? "reactive" : (a.rest() ? "servlet" : "none"));
    boolean web = caps.stream().anyMatch(cap -> cap.name().equals("web"));
    if (web) {
      app.put("server.port", 8080);
      app.put("server.shutdown", "graceful");
    }
    if (r.stack().id().equals("servlet")) {
      app.put("spring.threads.virtual.enabled", true);
    }
    app.put("spring.lifecycle.timeout-per-shutdown-phase", "20s");
    String store = Catalog.store(r.persistence(), r.stack());
    boolean aws =
        caps.stream()
            .anyMatch(
                cap ->
                    cap.localProperties().containsKey("axiz.aws.endpoint")
                        || cap.dependencies(store).stream()
                            .anyMatch(
                                d ->
                                    d.startsWith("software.amazon.awssdk:")
                                        || d.endsWith("-dynamodb")));
    if (aws) {
      app.put("axiz.aws.region", "${AWS_REGION:us-east-1}");
    }
    boolean messaging =
        caps.stream().anyMatch(cap -> cap.starter().equals("axiz-messaging-spring-boot-starter"));
    if (messaging) {
      app.put("axiz.messaging.type-prefix", r.groupId() + ".");
    }
    app.putAll(a.properties());
    String asyncApi = r.asyncApi();
    if (r.structure() == Structure.EDA && (asyncApi == null || asyncApi.isBlank())) {
      app.put("axiz.messaging.consumer.subscriptions[0]", r.groupId() + ".example.created.v1");
    }
    caps.forEach(cap -> app.putAll(cap.properties(store)));
    String restContract = r.openApi();
    if (web && restContract != null) {
      var publicPaths =
          OpenApiDocument.parse(restContract).operations().stream()
              .filter(op -> op.access() == ApiOperation.Access.PUBLIC)
              .map(ApiOperation::path)
              .distinct()
              .toList();
      if (!publicPaths.isEmpty()) app.put("axiz.security.public-paths", publicPaths);
    }
    boolean audit = caps.stream().anyMatch(cap -> cap.name().equals("audit"));
    boolean outbox = caps.stream().anyMatch(cap -> cap.name().equals("outbox"));
    if (audit) {
      app.put("axiz.audit.mode", outbox ? "DURABLE" : "ASYNC");
      if (outbox) {
        boolean kafka = caps.stream().anyMatch(cap -> cap.name().equals("messaging-kafka"));
        app.put(
            "axiz.messaging.destinations.audit",
            kafka ? "${AXIZ_AUDIT_DESTINATION:kafka:audit}" : "${AXIZ_AUDIT_DESTINATION}");
      }
    }

    if (asyncApi != null && !asyncApi.isBlank()) {
      app.keySet()
          .removeIf(
              key ->
                  key.equals("axiz.messaging.consumer.subscriptions")
                      || key.startsWith("axiz.messaging.consumer.subscriptions["));
      var consumed = AsyncApiDocument.parse(asyncApi).consumed();
      for (int i = 0; i < consumed.size(); i++)
        app.put("axiz.messaging.consumer.subscriptions[" + i + "]", consumed.get(i).type());
    }

    Map<String, Object> local = new LinkedHashMap<>();
    local.put("axiz.logging.json", false);
    local.put("axiz.config.environment", "local");
    local.put("axiz.transport.allow-insecure-data-stores", true);
    if (caps.stream().anyMatch(cap -> cap.name().equals("jpa"))) {
      local.put(
          "spring.datasource.url", "jdbc:postgresql://localhost:5432/" + Names.snake(r.name()));
      local.put("spring.datasource.username", "app");
      local.put("spring.datasource.hikari.data-source-properties.sslmode", "disable");
      local.put("spring.flyway.jdbc-properties.sslmode", "disable");
    }
    if (caps.stream().anyMatch(cap -> cap.name().equals("r2dbc"))) {
      local.put("spring.r2dbc.url", "r2dbc:postgresql://localhost:5432/" + Names.snake(r.name()));
      local.put("spring.r2dbc.username", "app");
      // the local PostgreSQL container has no TLS
      local.put("spring.r2dbc.properties.sslMode", "disable");
    }
    caps.forEach(cap -> local.putAll(cap.localProperties()));
    if (caps.stream().anyMatch(cap -> cap.name().equals("messaging-kafka")))
      local.put("spring.kafka.security.protocol", "PLAINTEXT");
    if (caps.stream().anyMatch(cap -> cap.name().endsWith("redis")))
      local.put("spring.data.redis.ssl.enabled", false);
    var auditResources = auditResources(caps);
    String auditQueue = "http://localhost:4566/000000000000/" + r.name() + "-audit";
    if (Boolean.TRUE.equals(auditResources.get("sqsSink")))
      local.put("axiz.audit.sqs.queue-url", auditQueue);
    if (Boolean.TRUE.equals(auditResources.get("outboxSqs")))
      local.put("axiz.messaging.destinations.audit", auditQueue);
    if (Boolean.TRUE.equals(auditResources.get("outboxSns")))
      local.put(
          "axiz.messaging.destinations.audit",
          "arn:aws:sns:us-east-1:000000000000:" + r.name() + "-audit");

    Map<String, Object> config = new LinkedHashMap<>();
    config.put("applicationYaml", YamlProperties.dump(YamlProperties.nest(app), 0));
    config.put("localYaml", YamlProperties.dump(YamlProperties.nest(local), 0));
    config.put("configMapKeys", List.copyOf(configMapKeys));
    config.put("hasConfigMapKeys", !configMapKeys.isEmpty());
    List<Map<String, Object>> secretRows = new ArrayList<>();
    secrets.forEach(sec -> secretRows.add(Map.of("key", sec)));
    config.put("secrets", secretRows);
    config.put("hasSecrets", !secretRows.isEmpty());
    return config;
  }

  private static Map<String, Boolean> auditResources(List<Capability> caps) {
    var names = caps.stream().map(Capability::name).collect(Collectors.toSet());
    boolean durable =
        names.contains("audit") && names.contains("outbox") && !names.contains("messaging-kafka");
    boolean outboxSqs = durable && names.contains("messaging-sqs");
    boolean outboxSns = durable && !outboxSqs && names.contains("messaging-sns");
    boolean sqsSink = names.contains("audit-sqs");
    return Map.of(
        "queue",
        sqsSink || outboxSqs,
        "topic",
        outboxSns,
        "sqsSink",
        sqsSink,
        "outboxSqs",
        outboxSqs,
        "outboxSns",
        outboxSns,
        "required",
        sqsSink || outboxSqs || outboxSns);
  }

  private static String controllerDependency(
      ProjectRequest request, Map<String, Object> framework, String type) {
    return switch (request.structure()) {
      case HEXAGONAL ->
          request.cqrs()
              ? "pe.axiz.extension.cqrs."
                  + (Boolean.TRUE.equals(framework.get("reactiveHandlers")) ? "spring." : "")
                  + type
              : request.basePackage() + ".application.usecase." + type;
      case LAYERED -> request.basePackage() + ".service." + type;
      case BFF -> request.basePackage() + ".composition." + type;
      default -> throw new IllegalArgumentException("No HTTP dependency for this structure");
    };
  }

  static Map<String, Object> operation(ApiOperation op, String basePackage) {
    Map<String, Object> o = new LinkedHashMap<>();
    String className = Names.pascal(op.operationId());
    o.put("operationId", op.operationId());
    o.put("className", className);
    o.put("methodName", Names.camel(op.operationId()));
    o.put("kebab", Names.kebab(op.operationId()));
    o.put("httpMethod", op.method());
    o.put("mapping", Names.pascal(op.method().toLowerCase(Locale.ROOT)) + "Mapping");
    o.put("path", op.path());
    o.put("tag", op.tag());
    o.put("summary", op.summary().replace("*/", "* /"));
    o.put("readOnly", op.readOnly());
    o.put("write", !op.readOnly());
    o.put("authorizationExpression", op.authorizationExpression());
    o.put("requiresScopes", op.access() == ApiOperation.Access.SCOPED);
    o.put("authenticatedAccess", op.access() == ApiOperation.Access.AUTHENTICATED);
    o.put("publicEndpoint", op.access() == ApiOperation.Access.PUBLIC);
    o.put(
        "requiredAuthorities",
        op.scopes().stream()
            .map(scope -> "\"SCOPE_" + scope + "\"")
            .collect(Collectors.joining(", ")));
    o.put("policyRequestType", className + (op.readOnly() ? "Query" : "Command"));
    o.put(
        "partialAuthority",
        op.scopes().size() > 1 ? "SCOPE_" + op.scopes().getFirst() : "SCOPE_unrelated");
    String uri = op.path().replaceAll("\\{[^}]+}", "test");
    if (!op.queryParams().isEmpty())
      uri +=
          "?"
              + op.queryParams().stream()
                  .map(param -> URLEncoder.encode(param.name(), StandardCharsets.UTF_8) + "=test")
                  .collect(Collectors.joining("&"));
    o.put("testUri", uri.replace("\\", "\\\\").replace("\"", "\\\""));
    String request = javaType(op.requestSchema());
    String response = javaType(op.responseSchema());
    o.put("hasRequest", request != null);
    o.put("requestType", request == null ? "Void" : request);
    o.put("hasResponse", response != null);
    o.put("responseType", response == null ? "Void" : response);
    o.put("hasModel", request != null || response != null);
    TreeSet<String> models = new TreeSet<>();
    for (String t : Arrays.asList(request, response)) {
      if (t != null) {
        models.add(t.startsWith("List<") ? t.substring(5, t.length() - 1) : t);
      }
    }
    o.put("models", models.stream().map(m -> Map.of("name", m)).toList());
    String responseModel = response == null ? "" : response.replaceFirst("^List<(.*)>$", "$1");
    o.put("responseModel", responseModel);
    o.put("returnType", response == null ? "void" : response);
    o.put("hasResponseModel", !responseModel.isEmpty());
    o.put("responseIsList", response != null && response.startsWith("List<"));
    o.put(
        "usesList",
        (request != null && request.startsWith("List<"))
            || (response != null && response.startsWith("List<")));
    o.put("status", op.successStatus());
    o.put("created", op.successStatus() == 201);
    o.put("noContent", op.successStatus() == 204 || response == null);
    o.put("paged", op.paged());
    List<Map<String, Object>> params = new ArrayList<>();
    List<String> declarations = new ArrayList<>();
    List<String> components = new ArrayList<>();
    List<String> arguments = new ArrayList<>();
    for (int i = 0; i < op.pathParams().size(); i++) {
      String p = op.pathParams().get(i);
      String javaName = Names.identifier(p);
      params.add(Map.of("name", p, "javaName", javaName, "last", i == op.pathParams().size() - 1));
      declarations.add("@PathVariable(\"" + p + "\") String " + javaName);
      components.add("String " + javaName);
      arguments.add(javaName);
    }
    List<Map<String, Object>> query = new ArrayList<>();
    for (ApiOperation.QueryParam q : op.queryParams()) {
      String javaName = Names.identifier(q.name());
      query.add(Map.of("name", q.name(), "javaName", javaName, "required", q.required()));
      declarations.add(
          "@RequestParam(name = \""
              + q.name()
              + "\", required = "
              + q.required()
              + ") "
              + (q.required() ? "" : "@Nullable ")
              + "String "
              + javaName);
      components.add((q.required() ? "" : "@Nullable ") + "String " + javaName);
      arguments.add(javaName);
    }
    if (op.paged()) {
      declarations.add("@Paging PageRequest page");
      components.add("PageRequest page");
      arguments.add("page");
    }
    if (request != null) {
      declarations.add("@Valid @RequestBody " + request + " body");
      components.add(request + " body");
      arguments.add("body");
    }
    o.put("pathParams", params);
    o.put("hasPathParams", !params.isEmpty());
    o.put("queryParams", query);
    o.put("hasQueryParams", !query.isEmpty());
    o.put("optionalQuery", op.queryParams().stream().anyMatch(q -> !q.required()));
    o.put("controllerParams", String.join(", ", declarations));
    o.put("inputComponents", String.join(", ", components));
    o.put("inputArguments", String.join(", ", arguments));
    List<String> hexagonalArguments = new ArrayList<>(arguments);
    if (request != null) {
      String applicationType =
          request.startsWith("List<")
              ? "java.util.List<" + basePackage + ".application.model." + request.substring(5)
              : basePackage + ".application.model." + request;
      hexagonalArguments.set(
          hexagonalArguments.size() - 1,
          "mapper.convertValue(body, new TypeReference<" + applicationType + ">() {})");
    }
    o.put("hexagonalArguments", String.join(", ", hexagonalArguments));
    o.put("hasInput", !components.isEmpty());
    o.put("commandName", className + "Command");
    o.put("queryName", className + "Query");
    String example = op.requestExample();
    o.put("hasExample", example != null);
    o.put("exampleJson", example == null ? "" : example);
    o.put("exampleTestable", example != null && request != null && !request.startsWith("List<"));
    o.put("example", example == null ? "" : example.replace("\\", "\\\\").replace("\"", "\\\""));
    o.put("modelPackage", basePackage + ".api.model");
    return o;
  }

  static @Nullable String javaType(@Nullable String schema) {
    if (schema == null) {
      return null;
    }
    if (schema.startsWith("List<")) {
      return "List<" + Names.pascal(schema.substring(5, schema.length() - 1)) + ">";
    }
    return Names.pascal(schema);
  }
}
