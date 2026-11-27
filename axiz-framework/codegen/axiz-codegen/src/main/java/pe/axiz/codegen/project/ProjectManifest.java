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
 * El manifiesto {@code .axiz/project.yaml} de un proyecto generado: lo que la CLI necesita para
 * generar código coherente con el proyecto (estructura, stack, paquetes, capacidades y contrato).
 *
 * @param name nombre del servicio
 * @param groupId grupo de Maven
 * @param basePackage paquete Java base
 * @param archetype id del arquetipo
 * @param structure estilo
 * @param stack stack
 * @param persistence {@code none}, {@code aurora} o {@code dynamodb}
 * @param axizVersion versión de la estructura
 * @param openapi ruta del contrato, relativa al proyecto, si existe
 * @param asyncapi ruta de AsyncAPI, si existe
 * @param capabilities capacidades del proyecto
 * @param operations ids de las operaciones generadas hasta el momento
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

  /** Lee el manifiesto del proyecto de {@code dir}. */
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
    return ProjectManifest.builder()
        .name(required(m, "name"))
        .groupId(required(m, "groupId"))
        .basePackage(required(m, "basePackage"))
        .archetype(role)
        .structure(structure)
        .stack(m.containsKey("stack") ? enumValue(m, "stack", Stack::of) : Stack.SERVLET)
        .persistence(persistence)
        .axizVersion(required(m, "axizVersion"))
        .openapi(path(contract, "openapi"))
        .asyncapi(path(contract, "asyncapi"))
        .capabilities(names(m, "capabilities"))
        .operations(names(m, "operations"))
        .extensions(extensions)
        .build();
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

  /** Vuelve a escribir el manifiesto (con la misma disposición que el generado). */
  public void write(Path dir) throws IOException {
    StringBuilder b = new StringBuilder();
    b.append(
        "# Manifiesto del proyecto axiz: lo lee la CLI axiz (generate, add, contract sync, upgrade,"
            + " doctor).\n");
    b.append("# Se versiona en el repositorio; la CLI lo actualiza cuando cambia el proyecto.\n");
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
    return ProjectManifest.builder()
        .name(name)
        .groupId(groupId)
        .basePackage(basePackage)
        .archetype(archetype)
        .structure(structure)
        .stack(stack)
        .persistence(persistence)
        .axizVersion(axizVersion)
        .openapi(openapi)
        .asyncapi(asyncapi)
        .capabilities(newCapabilities)
        .operations(operations)
        .extensions(extensions)
        .build();
  }

  public ProjectManifest withOperations(List<String> newOperations) {
    return ProjectManifest.builder()
        .name(name)
        .groupId(groupId)
        .basePackage(basePackage)
        .archetype(archetype)
        .structure(structure)
        .stack(stack)
        .persistence(persistence)
        .axizVersion(axizVersion)
        .openapi(openapi)
        .asyncapi(asyncapi)
        .capabilities(capabilities)
        .operations(newOperations)
        .extensions(extensions)
        .build();
  }

  /** Cambia el nombre del despliegue sin cambiar el paquete Java declarado de forma explícita. */
  public ProjectManifest withName(String newName) {
    return ProjectManifest.builder()
        .name(newName)
        .groupId(groupId)
        .basePackage(basePackage)
        .archetype(archetype)
        .structure(structure)
        .stack(stack)
        .persistence(persistence)
        .axizVersion(axizVersion)
        .openapi(openapi)
        .asyncapi(asyncapi)
        .capabilities(capabilities)
        .operations(operations)
        .extensions(extensions)
        .build();
  }

  public ProjectManifest withAxizVersion(String version) {
    return ProjectManifest.builder()
        .name(name)
        .groupId(groupId)
        .basePackage(basePackage)
        .archetype(archetype)
        .structure(structure)
        .stack(stack)
        .persistence(persistence)
        .axizVersion(version)
        .openapi(openapi)
        .asyncapi(asyncapi)
        .capabilities(capabilities)
        .operations(operations)
        .extensions(extensions)
        .build();
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

  /** Construcción con nombre; build verifica los valores obligatorios y las invariantes. */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Estado mutable de construcción; se usa un builder por operación, nunca como estado compartido.
   */
  public static final class Builder {
    private @Nullable String name = null;
    private @Nullable String groupId = null;
    private @Nullable String basePackage = null;
    private @Nullable String archetype = null;
    private @Nullable Structure structure = null;
    private @Nullable Stack stack = null;
    private @Nullable String persistence = null;
    private @Nullable String axizVersion = null;
    private @Nullable String openapi = null;
    private @Nullable String asyncapi = null;
    private @Nullable List<String> capabilities = List.of();
    private @Nullable List<String> operations = List.of();
    private @Nullable List<Extension> extensions = List.of();

    private Builder() {}

    public Builder name(String value) {
      this.name = value;
      return this;
    }

    public Builder groupId(String value) {
      this.groupId = value;
      return this;
    }

    public Builder basePackage(String value) {
      this.basePackage = value;
      return this;
    }

    public Builder archetype(String value) {
      this.archetype = value;
      return this;
    }

    public Builder structure(Structure value) {
      this.structure = value;
      return this;
    }

    public Builder stack(Stack value) {
      this.stack = value;
      return this;
    }

    public Builder persistence(String value) {
      this.persistence = value;
      return this;
    }

    public Builder axizVersion(String value) {
      this.axizVersion = value;
      return this;
    }

    public Builder openapi(@Nullable String value) {
      this.openapi = value;
      return this;
    }

    public Builder asyncapi(@Nullable String value) {
      this.asyncapi = value;
      return this;
    }

    public Builder capabilities(List<String> value) {
      this.capabilities = List.copyOf(value);
      return this;
    }

    public Builder operations(List<String> value) {
      this.operations = List.copyOf(value);
      return this;
    }

    public Builder extensions(List<Extension> value) {
      this.extensions = List.copyOf(value);
      return this;
    }

    public ProjectManifest build() {
      return new ProjectManifest(
          Objects.requireNonNull(name, "name"),
          Objects.requireNonNull(groupId, "groupId"),
          Objects.requireNonNull(basePackage, "basePackage"),
          Objects.requireNonNull(archetype, "archetype"),
          Objects.requireNonNull(structure, "structure"),
          Objects.requireNonNull(stack, "stack"),
          Objects.requireNonNull(persistence, "persistence"),
          Objects.requireNonNull(axizVersion, "axizVersion"),
          openapi,
          asyncapi,
          Objects.requireNonNull(capabilities, "capabilities"),
          Objects.requireNonNull(operations, "operations"),
          Objects.requireNonNull(extensions, "extensions"));
    }
  }
}
