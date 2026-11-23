package pe.axiz.codegen;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import pe.axiz.codegen.model.Snapshots;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.model.Structure;
import pe.axiz.codegen.openapi.ApiOperation;
import pe.axiz.codegen.openapi.OpenApiDocument;
import pe.axiz.codegen.project.ProjectManifest;
import pe.axiz.codegen.render.JavaFormatter;
import pe.axiz.codegen.render.JavaSourceText;

/**
 * {@code axiz generate http-client <name> --contract <openapi>}: un cliente declarativo (interfaz
 * {@code @HttpExchange}, un método por operación) de una API de terceros o aguas abajo, sus modelos
 * como records y su registro en el grupo de servicios HTTP {@code <name>}, que la capacidad
 * http-client configura a partir de {@code axiz.http-client.clients.<name>.*} (URL base, timeouts,
 * reintentos, circuit breaker, OAuth2 client credentials, observaciones).
 */
public final class HttpClientGenerator {

  /**
   * Cliente generado.
   *
   * @param files archivos fuente nuevos
   * @param properties entradas de {@code application.yaml} por agregar
   * @param notes indicaciones de seguimiento
   */
  public record Result(
      Map<String, byte[]> files, Map<String, Object> properties, List<String> notes) {
    public Result {
      files = Snapshots.files(files);
      notes = List.copyOf(notes);
      properties = Snapshots.properties(properties);
    }

    @Override
    public Map<String, byte[]> files() {
      return Snapshots.files(files);
    }

    @Override
    public Map<String, Object> properties() {
      return Snapshots.properties(properties);
    }
  }

  private HttpClientGenerator() {}

  public static Result generate(ProjectManifest project, String name, String contractText) {
    if (!Names.isArtifactId(name)) {
      throw new IllegalArgumentException("Client name must be kebab-case, e.g. core-banking");
    }
    if (!project.isService()) {
      throw new IllegalArgumentException("HTTP clients are generated in services");
    }
    OpenApiDocument contract = OpenApiDocument.parse(contractText);
    Object info = contract.raw().get("info");
    Object role = info instanceof Map<?, ?> values ? values.get("x-axiz-archetype") : null;
    if (!(role instanceof String target) || target.isBlank())
      throw new IllegalArgumentException("Target contract must declare info.x-axiz-archetype");
    ServiceRoles.requireCall(project.archetype(), target);
    String segment = Names.packageSegment(name);
    String pkg =
        project.basePackage()
            + (project.structure() == Structure.HEXAGONAL
                ? ".adapter.out." + segment
                : ".client." + segment);
    String modelPkg = pkg + ".model";
    String type = Names.pascal(name) + "Client";
    boolean reactive = project.stack() == Stack.REACTIVE;
    Map<String, byte[]> files = new LinkedHashMap<>();
    String base = "src/main/java/" + Names.packagePath(pkg) + "/";

    files.put(".axiz/clients/" + name + ".yaml", utf8(contractText));

    Set<String> models = new TreeSet<>();
    Map<String, Object> schemas = map(map(contract.raw().get("components")).get("schemas"));
    schemas.forEach(
        (schemaName, schema) -> {
          String source = model(modelPkg, Names.pascal(schemaName), map(schema), schemas);
          if (source != null) {
            models.add(Names.pascal(schemaName));
            files.put(base + "model/" + Names.pascal(schemaName) + ".java", utf8(source));
          }
        });
    if (!models.isEmpty()) {
      files.put(
          base + "model/package-info.java",
          utf8(packageInfo(modelPkg, "Models of the " + name + " API.")));
    }

    StringBuilder methods = new StringBuilder();
    Set<String> imports = new TreeSet<>();
    imports.add("org.springframework.web.service.annotation.HttpExchange");
    for (ApiOperation op : contract.operations()) {
      String method =
          Names.identifier(
              op.operationId().isEmpty()
                  ? op.method().toLowerCase(Locale.ROOT) + "-" + op.path()
                  : op.operationId());
      String response = javaType(op.responseSchema());
      String request = javaType(op.requestSchema());
      imports.add(
          "org.springframework.web.service.annotation."
              + Names.pascal(op.method().toLowerCase(Locale.ROOT))
              + "Exchange");
      List<String> params = new ArrayList<>();
      for (String p : op.pathParams()) {
        imports.add("org.springframework.web.bind.annotation.PathVariable");
        params.add(
            "@PathVariable("
                + JavaSourceText.parameterLiteral(p)
                + ") String "
                + Names.identifier(p));
      }
      for (ApiOperation.QueryParam q : op.queryParams()) {
        imports.add("org.springframework.web.bind.annotation.RequestParam");
        if (!q.required()) {
          imports.add("org.jspecify.annotations.Nullable");
        }
        params.add(
            "@RequestParam(name = "
                + JavaSourceText.parameterLiteral(q.name())
                + ", required = "
                + q.required()
                + ") "
                + (q.required() ? "" : "@Nullable ")
                + "String "
                + Names.identifier(q.name()));
      }
      for (String p : op.pagingParams()) {
        imports.add("org.springframework.web.bind.annotation.RequestParam");
        imports.add("org.jspecify.annotations.Nullable");
        params.add(
            "@RequestParam(name = "
                + JavaSourceText.parameterLiteral(p)
                + ", required = false) @Nullable "
                + (p.equals("sort") ? "String " : "Integer ")
                + p);
      }
      for (ApiOperation.QueryParam h : op.headerParams()) {
        imports.add("org.springframework.web.bind.annotation.RequestHeader");
        if (!h.required()) {
          imports.add("org.jspecify.annotations.Nullable");
        }
        params.add(
            "@RequestHeader(name = "
                + JavaSourceText.parameterLiteral(h.name())
                + ", required = "
                + h.required()
                + ") "
                + (h.required() ? "" : "@Nullable ")
                + "String "
                + Names.identifier(h.name()));
      }
      if (request != null) {
        imports.add("org.springframework.web.bind.annotation.RequestBody");
        params.add("@RequestBody " + request + " body");
      }
      String returns =
          response == null
              ? (reactive ? "Mono<Void>" : "void")
              : (reactive ? "Mono<" + response + ">" : response);
      if (reactive) {
        imports.add("reactor.core.publisher.Mono");
      }
      if ((response != null && response.startsWith("List<"))
          || (request != null && request.startsWith("List<"))) {
        imports.add("java.util.List");
      }
      for (String t : new String[] {response, request}) {
        if (t != null) {
          String simple = t.startsWith("List<") ? t.substring(5, t.length() - 1) : t;
          if (models.contains(simple)) {
            imports.add(modelPkg + "." + simple);
          }
        }
      }
      methods
          .append("\n  /** ")
          .append(JavaSourceText.javadoc(op.summary()))
          .append(" (")
          .append(op.method())
          .append(' ')
          .append(JavaSourceText.javadoc(op.path()))
          .append("). */\n")
          .append("  @")
          .append(Names.pascal(op.method().toLowerCase(Locale.ROOT)))
          .append("Exchange(")
          .append(JavaSourceText.literal(op.path()))
          .append(")\n")
          .append("  ")
          .append(returns)
          .append(' ')
          .append(method)
          .append('(')
          .append(String.join(", ", params))
          .append(");\n");
    }
    StringBuilder client = new StringBuilder("package " + pkg + ";\n\n");
    imports.forEach(i -> client.append("import ").append(i).append(";\n"));
    client
        .append(
            contract.title().matches("(?i).*\\bapi$")
                ? "\n/**\n * Cliente de "
                : "\n/**\n * Cliente de la API ")
        .append(JavaSourceText.javadoc(contract.title()))
        .append(" (")
        .append(JavaSourceText.javadoc(contract.version()))
        .append("), grupo de servicios HTTP {@code ")
        .append(name)
        .append("}: la URL base, los timeouts, los reintentos,\n")
        .append(
            " * el circuit breaker y la autenticación provienen de {@code axiz.http-client.clients.")
        .append(name)
        .append(".*}.\n */\n")
        .append("@HttpExchange\npublic interface ")
        .append(type)
        .append(" {\n")
        .append(methods)
        .append("}\n");
    files.put(base + type + ".java", utf8(client.toString()));

    String config =
        "package "
            + pkg
            + ";\n\n"
            + "import org.springframework.context.annotation.Configuration;\n"
            + "import org.springframework.web.service.registry.HttpServiceGroup;\n"
            + "import org.springframework.web.service.registry.ImportHttpServices;\n\n"
            + "/** Registra {@link "
            + type
            + "} en el grupo de servicios HTTP {@code "
            + name
            + "}. */\n"
            + "@Configuration(proxyBeanMethods = false)\n"
            + "@ImportHttpServices(\n    group = \""
            + name
            + "\",\n    types = "
            + type
            + ".class,\n"
            + "    clientType = HttpServiceGroup.ClientType."
            + (reactive ? "WEB_CLIENT" : "REST_CLIENT")
            + ")\n"
            + "public class "
            + type
            + "Configuration {}\n";
    files.put(base + type + "Configuration.java", utf8(config));
    files.put(
        base + "package-info.java", utf8(packageInfo(pkg, "Client of the " + name + " API.")));

    Map<String, Object> properties = new LinkedHashMap<>();
    String env = name.toUpperCase(Locale.ROOT).replace('-', '_');
    properties.put(
        "axiz.http-client.clients." + name + ".base-url",
        "${" + env + "_BASE_URL:http://localhost:8089}");
    List<String> notes = new ArrayList<>();
    if (!project.capabilities().contains("http-client")) {
      notes.add("The client needs the http-client capability: axiz add http-client");
    }
    notes.add(
        "Set axiz.http-client.clients."
            + name
            + ".base-url (and auth) in the ConfigMap of each environment");
    return new Result(JavaFormatter.formatAll(files), properties, notes);
  }

  static @Nullable String model(
      String pkg, String name, Map<String, Object> schema, Map<String, Object> schemas) {
    Object enumValues = schema.get("enum");
    if (enumValues instanceof List<?> values && !values.isEmpty()) {
      StringBuilder b = new StringBuilder("package " + pkg + ";\n\n");
      boolean renamed =
          values.stream().anyMatch(v -> !constant(String.valueOf(v)).equals(String.valueOf(v)));
      if (renamed) {
        b.append("import com.fasterxml.jackson.annotation.JsonProperty;\n\n");
      }
      b.append("/** Valores de ")
          .append(name)
          .append(". */\npublic enum ")
          .append(name)
          .append(" {\n");
      Set<String> constants = new LinkedHashSet<>();
      for (int i = 0; i < values.size(); i++) {
        String v = String.valueOf(values.get(i));
        if (!constants.add(constant(v))) {
          throw new IllegalArgumentException("Enum values produce duplicate Java names in " + name);
        }
        if (!constant(v).equals(v)) {
          b.append("  @JsonProperty(").append(JavaSourceText.literal(v)).append(")\n");
        }
        b.append("  ").append(constant(v)).append(i == values.size() - 1 ? "\n" : ",\n");
      }
      return b.append("}\n").toString();
    }
    if (!"object".equals(schema.get("type")) && !schema.containsKey("properties")) {
      return null;
    }
    Set<String> required = new LinkedHashSet<>();
    if (schema.get("required") instanceof List<?> r) {
      r.forEach(x -> required.add(String.valueOf(x)));
    }
    Set<String> imports = new TreeSet<>();
    List<String> components = new ArrayList<>();
    Set<String> componentNames = new LinkedHashSet<>();
    List<String> copies = new ArrayList<>();
    StringBuilder doc = new StringBuilder();
    map(schema.get("properties"))
        .forEach(
            (prop, def) -> {
              String javaType = propertyType(map(def), imports, schemas);
              String id = Names.identifier(prop);
              if (!componentNames.add(id)) {
                throw new IllegalArgumentException(
                    "Properties produce duplicate Java names in " + name);
              }
              String annotations = "";
              if (!id.equals(prop)) {
                imports.add("com.fasterxml.jackson.annotation.JsonProperty");
                annotations += "@JsonProperty(" + JavaSourceText.literal(prop) + ") ";
              }
              if (!required.contains(prop)) {
                imports.add("org.jspecify.annotations.Nullable");
                annotations += "@Nullable ";
              }
              components.add(annotations + javaType + " " + id);
              String copy = null;
              if (javaType.startsWith("List<")) {
                copy = "List.copyOf(" + id + ")";
              } else if (javaType.startsWith("Map<")) {
                imports.add("java.util.Collections");
                imports.add("java.util.LinkedHashMap");
                copy = "Collections.unmodifiableMap(new LinkedHashMap<>(" + id + "))";
              }
              if (copy != null) {
                copies.add(
                    id
                        + " = "
                        + (required.contains(prop) ? "" : id + " == null ? null : ")
                        + copy
                        + ";");
              }
              Object description = map(def).get("description");
              doc.append(" * @param ")
                  .append(id)
                  .append(' ')
                  .append(
                      JavaSourceText.javadoc(
                          description == null ? prop : String.valueOf(description)))
                  .append('\n');
            });
    StringBuilder b = new StringBuilder("package " + pkg + ";\n\n");
    imports.forEach(i -> b.append("import ").append(i).append(";\n"));
    if (!imports.isEmpty()) {
      b.append('\n');
    }
    b.append("/**\n * ").append(name).append(".\n");
    if (!doc.isEmpty()) {
      b.append(" *\n").append(doc);
    }
    b.append(" */\npublic record ")
        .append(name)
        .append('(')
        .append(String.join(", ", components))
        .append(") {");
    if (!copies.isEmpty()) {
      b.append("\n  public ").append(name).append(" {\n");
      copies.forEach(copy -> b.append("    ").append(copy).append('\n'));
      b.append("  }\n");
    }
    b.append("}\n");
    return b.toString();
  }

  static String propertyType(
      Map<String, Object> def, Set<String> imports, Map<String, Object> schemas) {
    Object ref = def.get("$ref");
    if (ref != null) {
      String r = String.valueOf(ref);
      String target = r.substring(r.lastIndexOf('/') + 1);
      Map<String, Object> referenced = map(schemas.get(target));
      boolean generated =
          referenced.get("enum") instanceof List<?>
              || "object".equals(referenced.get("type"))
              || referenced.containsKey("properties");
      if (generated || !r.startsWith("#/components/schemas/") || referenced.isEmpty()) {
        return Names.pascal(target);
      }
      return propertyType(referenced, imports, Map.of());
    }
    String type = String.valueOf(def.getOrDefault("type", "object"));
    String format = String.valueOf(def.getOrDefault("format", ""));
    switch (type) {
      case "string" -> {
        switch (format) {
          case "date" -> {
            imports.add("java.time.LocalDate");
            return "LocalDate";
          }
          case "date-time" -> {
            imports.add("java.time.OffsetDateTime");
            return "OffsetDateTime";
          }
          case "uuid" -> {
            imports.add("java.util.UUID");
            return "UUID";
          }
          default -> {
            return "String";
          }
        }
      }
      case "integer" -> {
        return "int64".equals(format) ? "Long" : "Integer";
      }
      case "number" -> {
        imports.add("java.math.BigDecimal");
        return "BigDecimal";
      }
      case "boolean" -> {
        return "Boolean";
      }
      case "array" -> {
        imports.add("java.util.List");
        return "List<" + propertyType(map(def.get("items")), imports, schemas) + ">";
      }
      default -> {
        imports.add("java.util.Map");
        return "Map<String, Object>";
      }
    }
  }

  static String constant(String value) {
    String c =
        value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
    return c.isEmpty() || Character.isDigit(c.charAt(0)) ? "V_" + c : c;
  }

  private static @Nullable String javaType(@Nullable String schema) {
    if (schema == null) {
      return null;
    }
    if (schema.startsWith("List<")) {
      return "List<" + Names.pascal(schema.substring(5, schema.length() - 1)) + ">";
    }
    return Names.pascal(schema);
  }

  private static String packageInfo(String pkg, String description) {
    return "/** "
        + JavaSourceText.javadoc(description)
        + " */\n@NullMarked\npackage "
        + pkg
        + ";\n\nimport org.jspecify.annotations.NullMarked;\n";
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(@Nullable Object o) {
    return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
  }

  private static byte[] utf8(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }
}
