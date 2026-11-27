package pe.axiz.codegen.openapi;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reglas corporativas de contratos, las mismas que el ruleset de Spectral de axiz-code-standards,
 * que el initializr y la CLI aplican previo a generar código. La CI también ejecuta Spectral con el
 * ruleset completo.
 */
public final class OpenApiLinter {

  private static final Pattern PARAMETER = Pattern.compile("^[A-Za-z][A-Za-z0-9_.-]{0,63}$");
  private static final Pattern CAMEL = Pattern.compile("^[a-z][a-zA-Z0-9]*$");
  // Un segmento de ruta: kebab-case o un {parameter}. Se verifica segmento por segmento, por lo que
  // la coincidencia es lineal en la longitud de la ruta (sin repeticiones anidadas).
  private static final Pattern SEGMENT =
      Pattern.compile("^(?:[a-z0-9](?:[a-z0-9]|-(?=[a-z0-9]))*|\\{[a-zA-Z0-9]+\\})$");
  private static final Pattern VERSIONED = Pattern.compile("^/v[0-9]+/.*");
  private static final Pattern SENSITIVE_QUERY =
      Pattern.compile("(?i).*(card|pan|documentnumber|password|otp).*");
  private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete");

  private OpenApiLinter() {}

  private static boolean kebabPath(String path) {
    if (!path.startsWith("/")) {
      return false;
    }
    for (String segment : path.substring(1).split("/", -1)) {
      if (!SEGMENT.matcher(segment).matches()) {
        return false;
      }
    }
    return true;
  }

  public static List<LintFinding> lint(OpenApiDocument doc) {
    List<LintFinding> out = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    Map<String, Object> paths = OpenApiDocument.map(doc.raw().get("paths"));
    if (paths.isEmpty()) {
      out.add(
          new LintFinding(
              "axiz-paths-required", "error", "$.paths", "The contract declares no operations"));
    }
    for (Map.Entry<String, Object> p : paths.entrySet()) {
      String path = p.getKey();
      if (!kebabPath(path)) {
        out.add(
            new LintFinding(
                "axiz-paths-kebab-case",
                "error",
                "$.paths['" + path + "']",
                "Path segments must be kebab-case"));
      }
      if (!VERSIONED.matcher(path).matches()) {
        out.add(
            new LintFinding(
                "axiz-versioned-paths",
                "error",
                "$.paths['" + path + "']",
                "Paths must start with /v<n>/"));
      }
      checkParameters(doc.raw(), OpenApiDocument.map(p.getValue()), "$.paths['" + path + "']", out);
      for (Map.Entry<String, Object> m : OpenApiDocument.map(p.getValue()).entrySet()) {
        if (!METHODS.contains(m.getKey())) {
          continue;
        }
        String loc = "$.paths['" + path + "']." + m.getKey();
        Map<String, Object> op = OpenApiDocument.map(m.getValue());
        Object id = op.get("operationId");
        if (id == null) {
          out.add(
              new LintFinding(
                  "axiz-operation-id-required",
                  "error",
                  loc,
                  "operationId is required (it names the generated code)"));
        } else if (!CAMEL.matcher(String.valueOf(id)).matches()) {
          out.add(
              new LintFinding(
                  "axiz-operation-id-camel-case",
                  "error",
                  loc + ".operationId",
                  "operationId must be lowerCamelCase"));
        } else if (!ids.add(String.valueOf(id))) {
          out.add(
              new LintFinding(
                  "axiz-operation-id-unique",
                  "error",
                  loc + ".operationId",
                  "Duplicate operationId " + id));
        }
        checkResponses(op, loc, out);
        checkParameters(doc.raw(), op, loc, out);
        if ("post".equals(m.getKey()) && !declaresIdempotencyKey(op)) {
          out.add(
              new LintFinding(
                  "axiz-idempotency-key-on-post",
                  "warn",
                  loc,
                  "POST operations should accept the Idempotency-Key header"));
        }
      }
    }
    checkAccess(doc, out);
    checkSchemas(doc, out);
    return out;
  }

  private static final Pattern MONEY =
      Pattern.compile("^(amount|[a-z]+Amount|price|total|balance|fee)$");

  /**
   * Esquemas de componentes: los números monetarios declaran su escala y su rango (error); los
   * objetos deciden sobre los campos desconocidos y las cadenas están acotadas (advertencias). Los
   * mismos ids que el ruleset de Spectral.
   */
  private static void checkSchemas(OpenApiDocument doc, List<LintFinding> out) {
    Map<String, Object> schemas =
        OpenApiDocument.map(OpenApiDocument.map(doc.raw().get("components")).get("schemas"));
    for (Map.Entry<String, Object> s : schemas.entrySet()) {
      String loc = "$.components.schemas." + s.getKey();
      Map<String, Object> schema = OpenApiDocument.map(s.getValue());
      if ("object".equals(schema.get("type")) && !schema.containsKey("additionalProperties")) {
        out.add(
            new LintFinding(
                "axiz-closed-objects",
                "warn",
                loc,
                "Object schemas declare additionalProperties (false for requests)"));
      }
      for (Map.Entry<String, Object> p : OpenApiDocument.map(schema.get("properties")).entrySet()) {
        Map<String, Object> property = OpenApiDocument.map(p.getValue());
        String where = loc + ".properties." + p.getKey();
        Object type = property.get("type");
        if ("number".equals(type)
            && MONEY.matcher(p.getKey()).matches()
            && !(property.containsKey("multipleOf") && property.containsKey("maximum"))) {
          out.add(
              new LintFinding(
                  "axiz-exact-amounts",
                  "error",
                  where,
                  "Monetary numbers declare multipleOf (their decimal scale) and maximum"));
        }
        if ("string".equals(type)
            && !property.containsKey("maxLength")
            && !property.containsKey("enum")
            && !property.containsKey("format")
            && !property.containsKey("pattern")
            && !property.containsKey("const")) {
          out.add(
              new LintFinding(
                  "axiz-bounded-strings",
                  "warn",
                  where,
                  "String properties declare maxLength, an enum, a format or a pattern"));
        }
      }
    }
  }

  /**
   * Cada operación declara cómo se accede a ella: alcances de OAuth2/OpenID Connect o autenticación
   * bearer, heredados del documento o propios, o {@code security: []} para acceso público
   * explícito. El generador, {@code axiz contract lint}, {@code axiz doctor} y el Initializr
   * aplican esta misma regla.
   */
  private static void checkAccess(OpenApiDocument doc, List<LintFinding> out) {
    for (var operation : doc.operations()) {
      if (operation.access() == ApiOperation.Access.UNDECLARED
          || operation.access() == ApiOperation.Access.UNSUPPORTED)
        out.add(
            new LintFinding(
                "axiz-explicit-http-access",
                "error",
                "$.paths['" + operation.path() + "']." + operation.method(),
                "Operation "
                    + operation.operationId()
                    + " must declare a supported security requirement (OAuth2/OpenID Connect"
                    + " scopes or bearer authentication), inherited or per operation; use"
                    + " security: [] only for explicit public access"));
    }
    for (var publicOperation : doc.operations()) {
      if (publicOperation.access() != ApiOperation.Access.PUBLIC) continue;
      for (var authenticated : doc.operations()) {
        if (authenticated.access() == ApiOperation.Access.AUTHENTICATED
            && overlappingPaths(publicOperation.path(), authenticated.path()))
          out.add(
              new LintFinding(
                  "axiz-explicit-http-access",
                  "error",
                  "$.paths['" + publicOperation.path() + "']." + publicOperation.method(),
                  "Public operation "
                      + publicOperation.operationId()
                      + " overlaps authenticated-only operation "
                      + authenticated.operationId()
                      + "; public-paths are path based: use distinct paths or explicit scoped"
                      + " authorization"));
      }
    }
  }

  private static boolean overlappingPaths(String left, String right) {
    String[] a = left.split("/", -1);
    String[] b = right.split("/", -1);
    if (a.length != b.length) return false;
    for (int i = 0; i < a.length; i++)
      if (!a[i].equals(b[i]) && !a[i].startsWith("{") && !b[i].startsWith("{")) return false;
    return true;
  }

  private static void checkResponses(Map<String, Object> op, String loc, List<LintFinding> out) {
    for (Map.Entry<String, Object> r : OpenApiDocument.map(op.get("responses")).entrySet()) {
      String code = r.getKey();
      Map<String, Object> content =
          OpenApiDocument.map(OpenApiDocument.map(r.getValue()).get("content"));
      if ((code.startsWith("4") || code.startsWith("5"))
          && !content.isEmpty()
          && !content.containsKey("application/problem+json")) {
        out.add(
            new LintFinding(
                "axiz-problem-json-errors",
                "error",
                loc + ".responses['" + code + "']",
                "Error responses must use application/problem+json"));
      }
      if (code.startsWith("2")) {
        for (Map.Entry<String, Object> c : content.entrySet()) {
          Map<String, Object> media = OpenApiDocument.map(c.getValue());
          if (!media.containsKey("example") && !media.containsKey("examples")) {
            out.add(
                new LintFinding(
                    "axiz-examples-required",
                    "warn",
                    loc + ".responses['" + code + "']",
                    "Add an example (used by contract tests)"));
          }
        }
      }
    }
    Map<String, Object> body =
        OpenApiDocument.map(
            OpenApiDocument.map(OpenApiDocument.map(op.get("requestBody")).get("content"))
                .get("application/json"));
    if (!body.isEmpty() && !body.containsKey("example") && !body.containsKey("examples")) {
      out.add(
          new LintFinding(
              "axiz-examples-required",
              "warn",
              loc + ".requestBody",
              "Add a request example (used by contract tests)"));
    }
  }

  public static boolean safeParameterName(String name) {
    return PARAMETER.matcher(name).matches();
  }

  private static void checkParameters(
      Map<String, Object> root, Map<String, Object> op, String loc, List<LintFinding> out) {
    if (op.get("parameters") instanceof List<?> params) {
      for (Object p : params) {
        Map<String, Object> param = OpenApiDocument.deref(root, OpenApiDocument.map(p));
        if (!param.isEmpty()
            && !safeParameterName(String.valueOf(param.getOrDefault("name", "")))) {
          out.add(
              new LintFinding(
                  "axiz-safe-parameter-names",
                  "error",
                  loc + ".parameters",
                  "Parameter names must match [A-Za-z][A-Za-z0-9_.-]{0,63}"));
        }
        if ("query".equals(param.get("in"))
            && SENSITIVE_QUERY.matcher(String.valueOf(param.get("name"))).matches()) {
          out.add(
              new LintFinding(
                  "axiz-no-business-ids-in-query",
                  "error",
                  loc + ".parameters",
                  "Sensitive identifiers must not travel in the query string: "
                      + param.get("name")));
        }
      }
    }
  }

  private static boolean declaresIdempotencyKey(Map<String, Object> op) {
    if (op.get("parameters") instanceof List<?> params) {
      for (Object p : params) {
        Map<String, Object> param = OpenApiDocument.map(p);
        if ("Idempotency-Key".equalsIgnoreCase(String.valueOf(param.get("name")))
            || String.valueOf(param.getOrDefault("$ref", "")).contains("IdempotencyKey")) {
          return true;
        }
      }
    }
    return false;
  }
}
