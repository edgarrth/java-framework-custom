package pe.axiz.codegen.openapi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Compatibility check between two versions of a contract, from the point of view of existing
 * consumers: removed operations, new required inputs, removed or retyped response fields and
 * narrowed enums are breaking; new operations, optional inputs and response fields are not.
 */
public final class ContractDiff {

  /**
   * One difference.
   *
   * @param breaking whether existing consumers break
   * @param location operation and field, e.g. {@code POST /v1/orders request.amount}
   * @param message what changed
   */
  public record Change(boolean breaking, String location, String message) {}

  private ContractDiff() {}

  public static List<Change> compare(OpenApiDocument previous, OpenApiDocument current) {
    List<Change> out = new ArrayList<>();
    Map<String, Map<String, Object>> before = operations(previous);
    Map<String, Map<String, Object>> after = operations(current);
    before.forEach(
        (key, op) -> {
          if (!after.containsKey(key)) {
            out.add(new Change(true, key, "operation removed"));
          }
        });
    after.forEach(
        (key, op) -> {
          Map<String, Object> old = before.get(key);
          if (old == null) {
            out.add(new Change(false, key, "operation added"));
            return;
          }
          parameters(previous, old, key, current, op, out);
          Map<String, Object> oldBody = body(previous, old.get("requestBody"));
          Map<String, Object> newBody = body(current, op.get("requestBody"));
          if (oldBody.isEmpty()
              && !newBody.isEmpty()
              && Boolean.TRUE.equals(
                  OpenApiDocument.deref(current.raw(), OpenApiDocument.map(op.get("requestBody")))
                      .get("required"))) {
            out.add(new Change(true, key + " request", "required request body added"));
          }
          requestFields(previous, oldBody, current, newBody, key + " request", out, 0);
          responseFields(
              previous,
              success(previous, old),
              current,
              success(current, op),
              key + " response",
              out,
              0);
        });
    return out;
  }

  public static boolean hasBreaking(List<Change> changes) {
    return changes.stream().anyMatch(Change::breaking);
  }

  private static Map<String, Map<String, Object>> operations(OpenApiDocument doc) {
    Map<String, Map<String, Object>> out = new LinkedHashMap<>();
    OpenApiDocument.map(doc.raw().get("paths"))
        .forEach(
            (path, item) -> {
              Map<String, Object> pathItem = OpenApiDocument.map(item);
              for (String m : List.of("get", "post", "put", "patch", "delete")) {
                if (pathItem.get(m) instanceof Map<?, ?>) {
                  Map<String, Object> op =
                      new LinkedHashMap<>(OpenApiDocument.map(pathItem.get(m)));
                  List<Object> params = new ArrayList<>();
                  if (pathItem.get("parameters") instanceof List<?> common) {
                    params.addAll(common);
                  }
                  if (op.get("parameters") instanceof List<?> own) {
                    params.addAll(own);
                  }
                  op.put("parameters", params);
                  out.put(m.toUpperCase(Locale.ROOT) + " " + normalize(path), op);
                }
              }
            });
    return out;
  }

  /** Path with parameter names erased, so renaming a path variable is not a removal. */
  private static String normalize(String path) {
    return path.replaceAll("\\{[^}]+}", "{}");
  }

  private static void parameters(
      OpenApiDocument prev,
      Map<String, Object> old,
      String key,
      OpenApiDocument cur,
      Map<String, Object> op,
      List<Change> out) {
    Set<String> oldNames = new LinkedHashSet<>();
    for (Object p : list(old.get("parameters"))) {
      Map<String, Object> param = OpenApiDocument.deref(prev.raw(), OpenApiDocument.map(p));
      oldNames.add(param.get("in") + ":" + param.get("name"));
    }
    for (Object p : list(op.get("parameters"))) {
      Map<String, Object> param = OpenApiDocument.deref(cur.raw(), OpenApiDocument.map(p));
      String id = param.get("in") + ":" + param.get("name");
      if (!oldNames.contains(id)
          && Boolean.TRUE.equals(param.get("required"))
          && !"path".equals(param.get("in"))) {
        out.add(new Change(true, key + " " + id, "required parameter added"));
      }
    }
  }

  private static Map<String, Object> body(OpenApiDocument doc, @Nullable Object requestBody) {
    Map<String, Object> rb = OpenApiDocument.deref(doc.raw(), OpenApiDocument.map(requestBody));
    Map<String, Object> media =
        OpenApiDocument.map(OpenApiDocument.map(rb.get("content")).get("application/json"));
    return schema(doc, media.get("schema"));
  }

  private static Map<String, Object> success(OpenApiDocument doc, Map<String, Object> op) {
    for (Map.Entry<String, Object> r : OpenApiDocument.map(op.get("responses")).entrySet()) {
      if (r.getKey().startsWith("2")) {
        Map<String, Object> response =
            OpenApiDocument.deref(doc.raw(), OpenApiDocument.map(r.getValue()));
        return schema(
            doc,
            OpenApiDocument.map(
                    OpenApiDocument.map(response.get("content")).get("application/json"))
                .get("schema"));
      }
    }
    return Map.of();
  }

  private static Map<String, Object> schema(OpenApiDocument doc, @Nullable Object s) {
    return OpenApiDocument.deref(doc.raw(), OpenApiDocument.map(s));
  }

  private static void requestFields(
      OpenApiDocument prev,
      Map<String, Object> old,
      OpenApiDocument cur,
      Map<String, Object> now,
      String at,
      List<Change> out,
      int depth) {
    if (depth > 8 || old.isEmpty() || now.isEmpty()) {
      return;
    }
    Set<String> oldRequired = strings(old.get("required"));
    Map<String, Object> oldProps = OpenApiDocument.map(old.get("properties"));
    for (String r : strings(now.get("required"))) {
      if (!oldRequired.contains(r)) {
        out.add(
            new Change(
                true,
                at + "." + r,
                oldProps.containsKey(r) ? "field became required" : "required field added"));
      }
    }
    typeChanges(old, now, at, out);
    OpenApiDocument.map(now.get("properties"))
        .forEach(
            (name, def) -> {
              if (oldProps.containsKey(name)) {
                requestFields(
                    prev,
                    schema(prev, oldProps.get(name)),
                    cur,
                    schema(cur, def),
                    at + "." + name,
                    out,
                    depth + 1);
              }
            });
  }

  private static void responseFields(
      OpenApiDocument prev,
      Map<String, Object> old,
      OpenApiDocument cur,
      Map<String, Object> now,
      String at,
      List<Change> out,
      int depth) {
    if (depth > 8 || old.isEmpty()) {
      return;
    }
    if (now.isEmpty()) {
      out.add(new Change(true, at, "response body removed"));
      return;
    }
    typeChanges(old, now, at, out);
    Set<String> oldEnum = strings(old.get("enum"));
    Set<String> newEnum = strings(now.get("enum"));
    if (!oldEnum.isEmpty() && !newEnum.isEmpty() && !oldEnum.equals(newEnum)) {
      out.add(
          new Change(
              !newEnum.containsAll(oldEnum) || !oldEnum.containsAll(newEnum),
              at,
              "enum values changed (consumers may not know new values)"));
    }
    Map<String, Object> newProps = OpenApiDocument.map(now.get("properties"));
    OpenApiDocument.map(old.get("properties"))
        .forEach(
            (name, def) -> {
              if (!newProps.containsKey(name)) {
                out.add(new Change(true, at + "." + name, "response field removed"));
              } else {
                responseFields(
                    prev,
                    schema(prev, def),
                    cur,
                    schema(cur, newProps.get(name)),
                    at + "." + name,
                    out,
                    depth + 1);
              }
            });
    newProps.keySet().stream()
        .filter(n -> !OpenApiDocument.map(old.get("properties")).containsKey(n))
        .forEach(n -> out.add(new Change(false, at + "." + n, "response field added")));
    if ("array".equals(old.get("type")) && "array".equals(now.get("type"))) {
      responseFields(
          prev,
          schema(prev, old.get("items")),
          cur,
          schema(cur, now.get("items")),
          at + "[]",
          out,
          depth + 1);
    }
  }

  private static void typeChanges(
      Map<String, Object> old, Map<String, Object> now, String at, List<Change> out) {
    Object oldType = old.get("type");
    Object newType = now.get("type");
    if (oldType != null && newType != null && !oldType.equals(newType)) {
      out.add(new Change(true, at, "type changed from " + oldType + " to " + newType));
    }
    Object oldFormat = old.get("format");
    Object newFormat = now.get("format");
    if (oldFormat != null && newFormat != null && !oldFormat.equals(newFormat)) {
      out.add(new Change(true, at, "format changed from " + oldFormat + " to " + newFormat));
    }
  }

  private static Set<String> strings(@Nullable Object o) {
    Set<String> out = new LinkedHashSet<>();
    if (o instanceof List<?> l) {
      l.forEach(x -> out.add(String.valueOf(x)));
    }
    return out;
  }

  /** The list value, or an empty list when the key is absent or holds something else. */
  private static List<?> list(@Nullable Object value) {
    return value instanceof List<?> l ? l : List.of();
  }
}
