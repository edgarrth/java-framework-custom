package pe.axiz.codegen.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.codegen.model.Framework;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.openapi.SafeYaml;

/**
 * The {@code .axiz/project.yaml} manifest of a generated project: what the CLI needs to generate
 * code coherent with the project (framework, stack, packages, capabilities and contract).
 *
 * @param name service name
 * @param groupId Maven group
 * @param basePackage base Java package
 * @param archetype archetype id
 * @param framework style
 * @param stack stack
 * @param persistence {@code none}, {@code aurora} or {@code dynamodb}
 * @param axizVersion framework version
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
    Framework framework,
    Stack stack,
    String persistence,
    String axizVersion,
    @Nullable String openapi,
    @Nullable String asyncapi,
    List<String> capabilities,
    List<String> operations) {

  public static final String PATH = ".axiz/project.yaml";

  public ProjectManifest {
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
    Map<?, ?> contract = m.get("contract") instanceof Map<?, ?> c ? c : Map.of();
    return new ProjectManifest(
        required(m, "name"),
        required(m, "groupId"),
        required(m, "basePackage"),
        required(m, "archetype"),
        Framework.of(String.valueOf(m.containsKey("framework") ? m.get("framework") : "none")),
        Stack.of(String.valueOf(m.containsKey("stack") ? m.get("stack") : "servlet")),
        String.valueOf(m.containsKey("persistence") ? m.get("persistence") : "none"),
        required(m, "axizVersion"),
        optional(contract.get("openapi")),
        optional(contract.get("asyncapi")),
        strings(m.get("capabilities")),
        strings(m.get("operations")));
  }

  /** Writes the manifest back (same layout as the generated one). */
  public void write(Path dir) throws IOException {
    StringBuilder b = new StringBuilder();
    b.append(
        "# axiz project manifest: read by the axiz CLI (generate, add, contract sync, upgrade,"
            + " doctor).\n");
    b.append("# Keep it in version control; the CLI updates it when it changes the project.\n");
    b.append("schema: 1\n");
    b.append("name: ").append(name).append('\n');
    b.append("groupId: ").append(groupId).append('\n');
    b.append("basePackage: ").append(basePackage).append('\n');
    b.append("archetype: ").append(archetype).append('\n');
    b.append("framework: ").append(framework.id()).append('\n');
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
    Files.createDirectories(file.getParent());
    Files.writeString(file, b.toString(), StandardCharsets.UTF_8);
  }

  public boolean isService() {
    return framework != Framework.NONE;
  }

  public ProjectManifest withCapabilities(List<String> newCapabilities) {
    return new ProjectManifest(
        name,
        groupId,
        basePackage,
        archetype,
        framework,
        stack,
        persistence,
        axizVersion,
        openapi,
        asyncapi,
        newCapabilities,
        operations);
  }

  public ProjectManifest withOperations(List<String> newOperations) {
    return new ProjectManifest(
        name,
        groupId,
        basePackage,
        archetype,
        framework,
        stack,
        persistence,
        axizVersion,
        openapi,
        asyncapi,
        capabilities,
        newOperations);
  }

  public ProjectManifest withAxizVersion(String version) {
    return new ProjectManifest(
        name,
        groupId,
        basePackage,
        archetype,
        framework,
        stack,
        persistence,
        version,
        openapi,
        asyncapi,
        capabilities,
        operations);
  }

  private static String required(Map<?, ?> m, String key) throws IOException {
    Object v = m.get(key);
    if (v == null || String.valueOf(v).isBlank()) {
      throw new IOException("Missing '" + key + "' in " + PATH);
    }
    return String.valueOf(v);
  }

  private static @Nullable String optional(@Nullable Object v) {
    return v == null || "null".equals(String.valueOf(v)) ? null : String.valueOf(v);
  }

  private static List<String> strings(@Nullable Object o) {
    List<String> out = new ArrayList<>();
    if (o instanceof List<?> l) {
      l.forEach(e -> out.add(String.valueOf(e)));
    }
    return out;
  }
}
