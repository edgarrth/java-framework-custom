package pe.axiz.codegen.openapi;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Corporate contract rules, the same as the Spectral ruleset in axiz-code-standards, applied by the
 * initializr and the CLI before generating code. CI runs Spectral with the full ruleset as well.
 */
public final class OpenApiLinter {

  private static final Pattern CAMEL = Pattern.compile("^[a-z][a-zA-Z0-9]*$");
  // One path segment: kebab-case or a {parameter}. Checked segment by segment, so the match is
  // linear in the length of the path (no nested repetition).
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
        checkParameters(op, loc, out);
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
    return out;
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

  private static void checkParameters(Map<String, Object> op, String loc, List<LintFinding> out) {
    if (op.get("parameters") instanceof List<?> params) {
      for (Object p : params) {
        Map<String, Object> param = OpenApiDocument.map(p);
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
