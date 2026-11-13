package pe.axiz.codegen.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import pe.axiz.codegen.model.Catalog;
import pe.axiz.codegen.model.Extension;
import pe.axiz.codegen.model.FrameworkVersions;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.model.Structure;
import pe.axiz.codegen.openapi.SafeYaml;
import pe.axiz.codegen.output.SafePaths;

/**
 * The {@code .axiz/project.yaml} manifest of a generated project: what the CLI needs to generate
 * code coherent with the project (structure, stack, packages, capabilities and contract).
 *
 * @param name service name
 * @param groupId Maven group
 * @param basePackage base Java package
 * @param archetype archetype id
 * @param structure style
 * @param stack stack
 * @param persistence {@code none}, {@code aurora} or {@code dynamodb}
 * @param axizVersion structure version
 * @param openapi contract path, relative to the project, if any
 * @param asyncapi AsyncAPI path, if any
 * @param capabilities capabilities of the project
 * @param operations operation ids generated so far
 */
public record ProjectManifest(
    String name,
    String groupId,
    String basePackage,
    String archetype,
    Structure structure,
    Stack stack,
    String persistence,
    String axizVersion,
    @Nullable String openapi,
    @Nullable String asyncapi,
    List<String> capabilities,
    List<String> operations,
    List<Extension> extensions) {

  public static final String PATH = ".axiz/project.yaml";

  private static final Set<String> KEYS =
      Set.of(
          "schema",
          "kind",
          "name",
          "groupId",
          "basePackage",
          "archetype",
          "structure",
          "extensions",
          "stack",
          "persistence",
          "axizVersion",
          "contract",
          "capabilities",
          "operations");
  private static final Set<String> CONTRACT_KEYS = Set.of("openapi", "asyncapi");
  private static final List<String> KINDS = List.of("service", "platform");
  private static final List<String> PERSISTENCE = List.of("none", Catalog.AURORA, Catalog.DYNAMODB);

  public ProjectManifest(
      String name,
      String groupId,
      String basePackage,
      String archetype,
      Structure structure,
      Stack stack,
      String persistence,
      String axizVersion,
      @Nullable String openapi,
      @Nullable String asyncapi,
      List<String> capabilities,
      List<String> operations) {
    this(
        name,
        groupId,
        basePackage,
        archetype,
        structure,
        stack,
        persistence,
        axizVersion,
        openapi,
        asyncapi,
        capabilities,
        operations,
        List.of());
  }

  public boolean cqrs() {
    return extensions.contains(Extension.CQRS);
  }

  public ProjectManifest {
    FrameworkVersions.requireSupported(axizVersion);
    extensions = List.copyOf(extensions);
    if (new HashSet<>(extensions).size() != extensions.size())
      throw new IllegalArgumentException("Duplicate service extension");
    Objects.requireNonNull(name, "name");
    capabilities = List.copyOf(capabilities);
    operations = List.copyOf(operations);
  }

  /** Reads the manifest of the project in {@code dir}. */
  public static ProjectManifest read(Path dir) throws IOException {
    Path file = dir.resolve(PATH);
    if (!Files.isRegularFile(file)) {
      throw new IOException("Not an axiz project (missing " + PATH + ") in " + dir);
    }
    Object doc = SafeYaml.create(1_000_000).load(Files.readString(file, StandardCharsets.UTF_8));
    if (!(doc instanceof Map<?, ?> m)) {
      throw new IOException(PATH + " is not a YAML mapping");
    }
    Object schema = m.get("schema");
    if (!(schema instanceof Number number) || number.doubleValue() != 2) {
      throw new IOException("Unsupported project manifest schema: " + schema);
    }
    rejectUnknown(m, KEYS, PATH);
    String kind = required(m, "kind");
    if (!KINDS.contains(kind))
      throw new IOException("'kind' in " + PATH + " must be one of " + KINDS + ": " + kind);
    Structure structure = enumValue(m, "structure", Structure::of);
    if (kind.equals("service") != (structure != Structure.NONE))
      throw new IOException(
          "'kind: " + kind + "' does not match 'structure: " + structure.id() + "' in " + PATH);
    List<Extension> extensions = names(m, "extensions").stream().map(Extension::of).toList();
    String role = required(m, "archetype");
    if (Catalog.load().archetype(role).isEmpty())
      throw new IOException("Unsupported archetype: " + role);
    String persistence = m.containsKey("persistence") ? required(m, "persistence") : "none";
    if (!PERSISTENCE.contains(persistence))
      throw new IOException(
          "'persistence' in " + PATH + " must be one of " + PERSISTENCE + ": " + persistence);
    Object contractValue = m.get("contract");
    if (contractValue != null && !(contractValue instanceof Map<?, ?>))
      throw new IOException("'contract' in " + PATH + " must be a mapping");
    Map<?, ?> contract = contractValue == null ? Map.of() : (Map<?, ?>) contractValue;
    rejectUnknown(contract, CONTRACT_KEYS, PATH + " contract");
    return new ProjectManifest(
        required(m, "name"),
        required(m, "groupId"),
        required(m, "basePackage"),
        role,
        structure,
        m.containsKey("stack") ? enumValue(m, "stack", Stack::of) : Stack.SERVLET,
        persistence,
        required(m, "axizVersion"),
        path(contract, "openapi"),
        path(contract, "asyncapi"),
        names(m, "capabilities"),
        names(m, "operations"),
        extensions);
  }

  private static void rejectUnknown(Map<?, ?> map, Set<String> allowed, String where)
      throws IOException {
    for (Object key : map.keySet())
      if (!allowed.contains(String.valueOf(key)))
        throw new IOException(
            "Unknown key '"
                + key
                + "' in "
                + where
                + "; allowed: "
                + allowed.stream().sorted().toList());
  }

  /** Writes the manifest back (same layout as the generated one). */
  public void write(Path dir) throws IOException {
    StringBuilder b = new StringBuilder();
    b.append(
        "# axiz project manifest: read by the axiz CLI (generate, add, contract sync, upgrade,"
            + " doctor).\n");
    b.append("# Keep it in version control; the CLI updates it when it changes the project.\n");
    b.append("schema: 2\n");
    b.append("kind: ").append(isService() ? "service" : "platform").append('\n');
    b.append("name: ").append(name).append('\n');
    b.append("groupId: ").append(groupId).append('\n');
    b.append("basePackage: ").append(basePackage).append('\n');
    b.append("archetype: ").append(archetype).append('\n');
    b.append("structure: ").append(structure.id()).append('\n');
    b.append("extensions: [")
        .append(String.join(", ", extensions.stream().map(Extension::id).toList()))
        .append("]\n");
    b.append("stack: ").append(stack.id()).append('\n');
    b.append("persistence: ").append(persistence).append('\n');
    b.append("axizVersion: ").append(axizVersion).append('\n');
    b.append("contract:\n");
    b.append("  openapi: ").append(openapi == null ? "null" : openapi).append('\n');
    b.append("  asyncapi: ").append(asyncapi == null ? "null" : asyncapi).append('\n');
    b.append("capabilities:\n");
    capabilities.forEach(c -> b.append("  - ").append(c).append('\n'));
    b.append("operations:\n");
    if (operations.isEmpty()) {
      b.append("  []\n");
    }
    operations.forEach(o -> b.append("  - ").append(o).append('\n'));
    Path file = dir.resolve(PATH);
    Files.createDirectories(SafePaths.parentOf(file));
    Files.writeString(file, b.toString(), StandardCharsets.UTF_8);
  }

  public boolean isService() {
    return structure != Structure.NONE;
  }

  public ProjectManifest withCapabilities(List<String> newCapabilities) {
    return new ProjectManifest(
        name,
        groupId,
        basePackage,
        archetype,
        structure,
        stack,
        persistence,
        axizVersion,
        openapi,
        asyncapi,
        newCapabilities,
        operations,
        extensions);
  }

  public ProjectManifest withOperations(List<String> newOperations) {
    return new ProjectManifest(
        name,
        groupId,
        basePackage,
        archetype,
        structure,
        stack,
        persistence,
        axizVersion,
        openapi,
        asyncapi,
        capabilities,
        newOperations,
        extensions);
  }

  /** Changes the deployment name without changing the explicitly declared Java package. */
  public ProjectManifest withName(String newName) {
    return new ProjectManifest(
        newName,
        groupId,
        basePackage,
        archetype,
        structure,
        stack,
        persistence,
        axizVersion,
        openapi,
        asyncapi,
        capabilities,
        operations,
        extensions);
  }

  public ProjectManifest withAxizVersion(String version) {
    return new ProjectManifest(
        name,
        groupId,
        basePackage,
        archetype,
        structure,
        stack,
        persistence,
        version,
        openapi,
        asyncapi,
        capabilities,
        operations,
        extensions);
  }

  private static String required(Map<?, ?> m, String key) throws IOException {
    Object v = m.get(key);
    if (v == null || (v instanceof String blank && blank.isBlank())) {
      throw new IOException("Missing '" + key + "' in " + PATH);
    }
    if (!(v instanceof String text)) {
      throw new IOException("'" + key + "' in " + PATH + " must be text: " + v);
    }
    return text;
  }

  private static @Nullable String path(Map<?, ?> contract, String key) throws IOException {
    Object v = contract.get(key);
    if (v == null) return null;
    if (!(v instanceof String text) || text.isBlank())
      throw new IOException("'contract." + key + "' in " + PATH + " must be a path or null");
    return text;
  }

  private static List<String> names(Map<?, ?> m, String key) throws IOException {
    Object o = m.get(key);
    if (o == null) return List.of();
    if (!(o instanceof List<?> list))
      throw new IOException("'" + key + "' in " + PATH + " must be a list");
    List<String> out = new ArrayList<>();
    for (Object e : list) {
      if (!(e instanceof String name) || name.isBlank())
        throw new IOException("'" + key + "' in " + PATH + " must contain only names: " + e);
      out.add(name);
    }
    return out;
  }

  private static <T> T enumValue(Map<?, ?> m, String key, Function<String, T> parse)
      throws IOException {
    String value = required(m, key);
    try {
      return parse.apply(value);
    } catch (IllegalArgumentException e) {
      throw new IOException("Unsupported '" + key + "' in " + PATH + ": " + value, e);
    }
  }
}
