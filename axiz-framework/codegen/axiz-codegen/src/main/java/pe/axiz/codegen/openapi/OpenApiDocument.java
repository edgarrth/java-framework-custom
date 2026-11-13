package pe.axiz.codegen.openapi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import pe.axiz.codegen.model.Snapshots;

/**
 * Minimal, safe reader of OpenAPI 3.x documents (YAML or JSON): operations, tags, schemas and
 * examples needed by the generators. No remote {@code $ref} is ever resolved.
 */
public final class OpenApiDocument {

  public static final int MAX_SIZE = 2 * 1024 * 1024;
  private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete");

  private final Map<String, Object> root;
  private final List<ApiOperation> operations;

  private OpenApiDocument(Map<String, Object> root, List<ApiOperation> operations) {
    this.root = Snapshots.properties(root);
    this.operations = List.copyOf(operations);
  }

  /**
   * Parses a contract.
   *
   * @param text YAML or JSON
   * @return the document
   * @throws IllegalArgumentException when the text is not an OpenAPI 3 document
   */
  public static OpenApiDocument parse(String text) {
    if (text.length() > MAX_SIZE) {
      throw new IllegalArgumentException("Contract exceeds " + MAX_SIZE + " characters");
    }
    Object doc;
    try {
      doc = SafeYaml.create(MAX_SIZE).load(text);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException(
          "Contract is not valid YAML or JSON: " + e.getMessage(), e);
    }
    if (!(doc instanceof Map<?, ?> m)) {
      throw new IllegalArgumentException("Contract must be a mapping");
    }
    Map<String, Object> root = map(m);
    Object version = root.get("openapi");
    if (version == null || !String.valueOf(version).startsWith("3.")) {
      throw new IllegalArgumentException("Only OpenAPI 3.x contracts are supported");
    }
    List<ApiOperation> ops = new ArrayList<>();
    map(root.get("paths"))
        .forEach(
            (path, item) ->
                map(item)
                    .forEach(
                        (method, op) -> {
                          if (METHODS.contains(method)) {
                            ops.add(
                                operation(
                                    root,
                                    path,
                                    method.toUpperCase(Locale.ROOT),
                                    map(op),
                                    map(item)));
                          }
                        }));
    return new OpenApiDocument(root, ops);
  }

  public List<ApiOperation> operations() {
    return operations;
  }

  public String title() {
    return String.valueOf(map(root.get("info")).getOrDefault("title", "API"));
  }

  public String version() {
    return String.valueOf(map(root.get("info")).getOrDefault("version", "1.0.0"));
  }

  public List<String> tags() {
    return operations.stream().map(ApiOperation::tag).distinct().toList();
  }

  public List<String> schemas() {
    return new ArrayList<>(map(map(root.get("components")).get("schemas")).keySet());
  }

  public Map<String, Object> raw() {
    return Snapshots.properties(root);
  }

  /**
   * Resolves a local reference ({@code #/components/...}); remote references are never followed and
   * resolve to an empty object.
   */
  static Map<String, Object> deref(Map<String, Object> root, Map<String, Object> node) {
    Map<String, Object> current = node;
    for (int depth = 0; depth < 10 && current.get("$ref") instanceof String ref; depth++) {
      if (!ref.startsWith("#/")) {
        return Map.of();
      }
      Map<String, Object> target = root;
      for (String part : ref.substring(2).split("/", -1)) {
        target = map(target.get(part.replace("~1", "/").replace("~0", "~")));
      }
      current = target;
    }
    return current;
  }

  private static ApiOperation operation(
      Map<String, Object> root,
      String path,
      String method,
      Map<String, Object> op,
      Map<String, Object> item) {
    String operationId = String.valueOf(op.getOrDefault("operationId", ""));
    List<?> tags = op.get("tags") instanceof List<?> l ? l : List.of();
    String tag = tags.isEmpty() ? "default" : String.valueOf(tags.get(0));
    List<String> pathParams = new ArrayList<>();
    List<ApiOperation.QueryParam> queryParams = new ArrayList<>();
    boolean paged = false;
    List<String> pagingParams = new ArrayList<>();
    List<ApiOperation.QueryParam> headerParams = new ArrayList<>();
    List<Object> params = new ArrayList<>();
    if (item.get("parameters") instanceof List<?> common) {
      params.addAll(common);
    }
    if (op.get("parameters") instanceof List<?> own) {
      params.addAll(own);
    }
    for (Object p : params) {
      Map<String, Object> param = deref(root, map(p));
      String name = String.valueOf(param.getOrDefault("name", ""));
      if (name.isEmpty()) {
        continue;
      }
      if ("path".equals(param.get("in")) && !pathParams.contains(name)) {
        pathParams.add(name);
      }
      if ("query".equals(param.get("in"))) {
        if (name.equals("page") || name.equals("size") || name.equals("sort")) {
          paged |= !name.equals("sort");
          if (!pagingParams.contains(name)) {
            pagingParams.add(name);
          }
        } else if (queryParams.stream().noneMatch(q -> q.name().equals(name))) {
          queryParams.add(
              new ApiOperation.QueryParam(name, Boolean.TRUE.equals(param.get("required"))));
        }
      }
      if ("header".equals(param.get("in"))
          && headerParams.stream().noneMatch(h -> h.name().equalsIgnoreCase(name))) {
        headerParams.add(
            new ApiOperation.QueryParam(name, Boolean.TRUE.equals(param.get("required"))));
      }
    }
    Map<String, Object> json =
        map(map(deref(root, map(op.get("requestBody"))).get("content")).get("application/json"));
    String requestSchema = schemaName(json.get("schema"));
    String requestExample = example(json);
    int success = 200;
    String responseSchema = null;
    for (Map.Entry<String, Object> r : map(op.get("responses")).entrySet()) {
      if (r.getKey().startsWith("2")) {
        success = Integer.parseInt(r.getKey());
        responseSchema =
            schemaName(
                map(map(deref(root, map(r.getValue())).get("content")).get("application/json"))
                    .get("schema"));
        break;
      }
    }
    Object security = op.containsKey("security") ? op.get("security") : root.get("security");
    List<String> scopes = scopes(security);
    ApiOperation.Access access = access(root, security, scopes);
    if (access != ApiOperation.Access.SCOPED) scopes = List.of();
    return new ApiOperation(
        operationId,
        method,
        path,
        tag,
        String.valueOf(op.getOrDefault("summary", operationId)),
        requestSchema,
        responseSchema,
        success,
        pathParams,
        paged,
        requestExample,
        queryParams,
        pagingParams,
        headerParams,
        scopes,
        access);
  }

  private static ApiOperation.Access access(
      Map<String, Object> root, @Nullable Object security, List<String> scopes) {
    if (security == null) return ApiOperation.Access.UNDECLARED;
    if (!(security instanceof List<?> alternatives)) return ApiOperation.Access.UNSUPPORTED;
    if (alternatives.isEmpty()) return ApiOperation.Access.PUBLIC;
    if (alternatives.size() != 1) return ApiOperation.Access.UNSUPPORTED;
    var requirement = map(alternatives.getFirst());
    if (requirement.size() != 1) return ApiOperation.Access.UNSUPPORTED;
    String schemeName = requirement.keySet().iterator().next();
    var scheme =
        deref(root, map(map(map(root.get("components")).get("securitySchemes")).get(schemeName)));
    Object type = scheme.get("type");
    boolean oauth = "oauth2".equals(type) || "openIdConnect".equals(type);
    boolean bearer =
        "http".equals(type) && "bearer".equalsIgnoreCase(String.valueOf(scheme.get("scheme")));
    if ((!oauth && !bearer) || (bearer && !scopes.isEmpty()))
      return ApiOperation.Access.UNSUPPORTED;
    return scopes.isEmpty() ? ApiOperation.Access.AUTHENTICATED : ApiOperation.Access.SCOPED;
  }

  /** Multiple security objects are OR alternatives and require an explicit application policy. */
  private static List<String> scopes(@Nullable Object security) {
    if (!(security instanceof List<?> alternatives) || alternatives.size() != 1) return List.of();
    var requirement = map(alternatives.getFirst());
    if (requirement.size() != 1) return List.of();
    var required = new LinkedHashSet<String>();
    for (Object values : requirement.values()) {
      if (!(values instanceof List<?> scopes))
        throw new IllegalArgumentException("Security scopes must be a list");
      for (Object item : scopes) {
        if (!(item instanceof String scope) || !scope.matches("[A-Za-z0-9:_./-]{1,128}"))
          throw new IllegalArgumentException("Unsupported security scope");
        required.add(scope);
      }
    }
    return List.copyOf(required);
  }

  private static @Nullable String example(Map<String, Object> mediaType) {
    Object ex = mediaType.get("example");
    if (ex == null
        && mediaType.get("examples") instanceof Map<?, ?> examples
        && !examples.isEmpty()) {
      ex = map(examples.values().iterator().next()).get("value");
    }
    return ex == null ? null : Json.write(ex);
  }

  /** Schema name for a {@code $ref}, {@code List<Name>} for arrays of references, else null. */
  static @Nullable String schemaName(@Nullable Object schema) {
    Map<String, Object> s = map(schema);
    Object ref = s.get("$ref");
    if (ref != null) {
      String r = String.valueOf(ref);
      return r.substring(r.lastIndexOf('/') + 1);
    }
    if ("array".equals(s.get("type"))) {
      String item = schemaName(s.get("items"));
      return item == null ? null : "List<" + item + ">";
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> map(@Nullable Object o) {
    if (o instanceof Map<?, ?> m) {
      Map<String, Object> out = new LinkedHashMap<>();
      m.forEach((k, v) -> out.put(String.valueOf(k), v));
      return out;
    }
    return Map.of();
  }
}
