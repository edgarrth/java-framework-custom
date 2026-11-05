package pe.axiz.codegen.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import pe.axiz.codegen.model.Capability;
import pe.axiz.codegen.model.Catalog;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.output.SafePaths;
import pe.axiz.codegen.render.TemplateSet;
import pe.axiz.codegen.render.YamlProperties;

/**
 * {@code axiz add <capability>}: adds the starter and its adapters to the pom, its properties to
 * {@code application.yaml} (and the local profile), the ConfigMap keys to the Helm values of every
 * environment, its secrets to the ExternalSecret, the tables it needs as a new Flyway migration and
 * the capability to the manifest. Required capabilities are added too.
 */
public final class CapabilityAdder {

  /**
   * What was done.
   *
   * @param added capabilities added (requirements included), empty when already present
   * @param changed files changed or created, relative to the project
   * @param configMapKeys ConfigMap keys each environment must now define
   * @param example usage example of the requested capability
   */
  public record Result(
      List<String> added, List<String> changed, List<String> configMapKeys, String example) {
    public Result {
      added = List.copyOf(added);
      changed = List.copyOf(changed);
      configMapKeys = List.copyOf(configMapKeys);
    }
  }

  private static final Map<String, String> SCHEMAS =
      Map.of(
          "outbox",
          "outbox",
          "inbox",
          "inbox",
          "saga",
          "saga",
          "sequence",
          "sequence",
          "scheduling",
          "shedlock");

  private final Catalog catalog;

  public CapabilityAdder(Catalog catalog) {
    this.catalog = catalog;
  }

  /**
   * Adds a capability to the project in {@code dir}.
   *
   * @throws IllegalArgumentException when the capability is unknown or incompatible with the
   *     project
   */
  public Result add(Path dir, String capability) throws IOException {
    dir = dir.toAbsolutePath().normalize();
    ProjectManifest manifest = ProjectManifest.read(dir);
    if (!manifest.isService()) {
      throw new IllegalArgumentException("Capabilities apply to services only");
    }
    Capability requested =
        catalog
            .capability(capability)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Unknown capability "
                            + capability
                            + "; available: "
                            + String.join(
                                ", ",
                                catalog.capabilities().stream().map(Capability::name).toList())));
    String persistence = manifest.persistence();
    String store = Catalog.store(persistence, manifest.stack());
    List<Capability> missing =
        catalog.resolve(List.of(capability), store).stream()
            .filter(c -> !manifest.capabilities().contains(c.name()))
            .toList();
    if (missing.isEmpty()) {
      return new Result(List.of(), List.of(), List.of(), requested.example());
    }
    for (Capability c : missing) {
      if (!c.supports(manifest.stack()))
        throw new IllegalArgumentException(
            "Capability " + c.name() + " does not support " + manifest.stack().id());
      if (Set.of("ux", "core", "gateway").contains(manifest.archetype())
          && Set.of("messaging", "inbox", "outbox", "notification").contains(c.name()))
        throw new IllegalArgumentException(
            "Archetype " + manifest.archetype() + " cannot use domain messaging");
      if (c.name().equals("jpa") && !"aurora".equals(persistence)) {
        throw new IllegalArgumentException(
            "Capability " + capability + " needs Aurora (JPA); the project uses " + persistence);
      }
      if (c.name().equals("jpa") && manifest.stack() == Stack.REACTIVE) {
        throw new IllegalArgumentException(
            "Capability "
                + capability
                + " needs JPA, which is blocking: not available on the reactive stack");
      }
      if (!c.supports(store)) {
        throw new IllegalArgumentException(
            "Capability "
                + c.name()
                + " needs "
                + String.join(" or ", c.stores().stream().map(Catalog::storeLabel).toList())
                + " (project uses "
                + Catalog.storeLabel(store)
                + ")");
      }
    }
    ProjectLayout layout = ProjectLayout.read(dir, manifest);
    List<Path> charts = layout.charts();
    SafePaths.target(dir, ProjectManifest.PATH);
    layout.target("src/main/resources/application.yaml");
    layout.target("src/main/resources/application-local.yaml");
    layout.target(Migrations.DIR);
    for (Path chart : charts) {
      try (Stream<Path> entries = Files.list(chart)) {
        for (Path file : entries.toList()) SafePaths.target(dir, dir.relativize(file).toString());
      }
    }
    List<String> changed = new ArrayList<>();

    Path pom = layout.pom();
    String pomText = Files.readString(pom, StandardCharsets.UTF_8);
    String updated = pomText;
    for (Capability c : missing) {
      updated = PomEditor.addDependency(updated, "pe.axiz", c.starter(), null);
      for (String d : c.dependencies(store)) {
        String[] ga = d.split(":", -1);
        updated =
            PomEditor.addDependency(
                updated, ga[0], ga[1], ga[1].startsWith("axiz-adapter-") ? "runtime" : null);
      }
    }
    if (!updated.equals(pomText)) {
      Files.writeString(pom, updated, StandardCharsets.UTF_8);
      changed.add(dir.relativize(pom).toString());
    }

    Map<String, Object> properties = new LinkedHashMap<>();
    Map<String, Object> local = new LinkedHashMap<>();
    Set<String> keys = new LinkedHashSet<>();
    Set<String> secrets = new LinkedHashSet<>();
    for (Capability c : missing) {
      properties.putAll(c.properties(store));
      local.putAll(c.localProperties());
      keys.addAll(c.configMapKeys());
      secrets.addAll(c.secrets());
    }
    if (mergeYaml(layout.target("src/main/resources/application.yaml"), properties)) {
      changed.add(layout.relative("src/main/resources/application.yaml"));
    }
    if (mergeYaml(layout.target("src/main/resources/application-local.yaml"), local)) {
      changed.add(layout.relative("src/main/resources/application-local.yaml"));
    }

    for (Path chart : charts) {
      if (!keys.isEmpty()) {
        try (Stream<Path> files = Files.list(chart)) {
          for (Path values :
              files
                  .filter(p -> SafePaths.fileName(p).matches("values-[a-z0-9-]+\\.yaml"))
                  .sorted()
                  .toList()) {
            appendConfigKeys(values, capability, keys);
            changed.add(dir.relativize(values).toString());
          }
        }
      }
      if (!secrets.isEmpty() && addSecrets(chart.resolve("values.yaml"), secrets)) {
        changed.add(dir.relativize(chart.resolve("values.yaml")).toString());
      }
    }

    if ("aurora".equals(persistence)) {
      StringBuilder sql = new StringBuilder();
      TemplateSet service = TemplateSet.load("service");
      for (Capability c : missing) {
        String schema = SCHEMAS.get(c.name());
        if (schema != null) {
          sql.append('\n')
              .append(service.renderTemplate("partials/sql/" + schema + ".mustache", Map.of()));
        }
      }
      if (!sql.isEmpty()) {
        String version = Migrations.next(layout.module());
        Path migration =
            layout.target(
                Migrations.DIR
                    + "/V"
                    + version
                    + "__axiz_"
                    + capability.replace('-', '_')
                    + ".sql");
        Files.createDirectories(SafePaths.parentOf(migration));
        Files.writeString(
            migration,
            "-- Tables of the " + capability + " capability (added by axiz add)\n" + sql,
            StandardCharsets.UTF_8);
        changed.add(dir.relativize(migration).toString());
      }
    }

    List<String> all = new ArrayList<>(manifest.capabilities());
    missing.forEach(c -> all.add(c.name()));
    manifest.withCapabilities(all).write(dir);
    changed.add(ProjectManifest.PATH);
    return new Result(
        missing.stream().map(Capability::name).toList(),
        changed,
        List.copyOf(keys),
        requested.example());
  }

  /**
   * Merges properties into a YAML file, keeping its leading comment block; true when it changed.
   */
  public static boolean mergeYaml(Path file, Map<String, Object> properties) throws IOException {
    return mergeYaml(file, properties, List.of());
  }

  /** Like {@link #mergeYaml(Path, Map)}, dropping existing keys under {@code removePrefixes}. */
  public static boolean mergeYaml(
      Path file, Map<String, Object> properties, List<String> removePrefixes) throws IOException {
    if (properties.isEmpty()) {
      return false;
    }
    String current =
        Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
    StringBuilder header = new StringBuilder();
    for (String line : current.lines().toList()) {
      if (!line.startsWith("#")) {
        break;
      }
      header.append(line).append('\n');
    }
    String merged = header + YamlProperties.merge(current, properties, removePrefixes);
    if (merged.equals(current)) {
      return false;
    }
    Files.createDirectories(SafePaths.parentOf(file));
    Files.writeString(file, merged, StandardCharsets.UTF_8);
    return true;
  }

  static void appendConfigKeys(Path values, String capability, Set<String> keys)
      throws IOException {
    StringBuilder b = new StringBuilder(Files.readString(values, StandardCharsets.UTF_8));
    if (b.length() > 0 && b.charAt(b.length() - 1) != '\n') {
      b.append('\n');
    }
    b.append("# Added by axiz add ").append(capability).append(": set these keys under config:\n");
    keys.forEach(k -> b.append("#   ").append(k).append(":\n"));
    Files.writeString(values, b.toString(), StandardCharsets.UTF_8);
  }

  /** Adds keys to {@code externalSecret.keys} of the chart values. */
  static boolean addSecrets(Path values, Set<String> secrets) throws IOException {
    if (!Files.isRegularFile(values)) {
      return false;
    }
    List<String> lines = new ArrayList<>(Files.readAllLines(values, StandardCharsets.UTF_8));
    int keysLine = -1;
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).equals("  keys:") && i > 0) {
        keysLine = i;
      }
    }
    if (keysLine < 0) {
      return false;
    }
    int end = keysLine + 1;
    Set<String> present = new LinkedHashSet<>();
    while (end < lines.size() && lines.get(end).startsWith("    ")) {
      String item = lines.get(end).strip();
      if (item.startsWith("- ")) {
        present.add(item.substring(2).strip());
      }
      end++;
    }
    present.addAll(secrets);
    List<String> replacement = present.stream().map(s -> "    - " + s).toList();
    lines.subList(keysLine + 1, end).clear();
    lines.addAll(keysLine + 1, replacement);
    Files.write(values, lines, StandardCharsets.UTF_8);
    return true;
  }
}
