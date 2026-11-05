package pe.axiz.codegen.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import pe.axiz.codegen.ServiceRoles;
import pe.axiz.codegen.openapi.SafeYaml;
import pe.axiz.codegen.output.SafePaths;
import pe.axiz.codegen.render.YamlProperties;

/** Preserves identities across deployment renames, with a version-controlled doctor ledger. */
public final class ProjectRename {
  public static final String LEDGER = ".axiz/identities.yaml";
  private static final List<String> STABLE =
      List.of(
          "axiz.messaging.source",
          "axiz.messaging.consumer.name",
          "axiz.messaging.kafka.group-id",
          "axiz.config.parameter-store.application");

  private ProjectRename() {}

  /** Builds all edits before writing; explicit resources and Java packages keep their identity. */
  public static void apply(Path dir, ProjectManifest manifest, String name, boolean dryRun)
      throws IOException {
    if (!manifest.isService() || !ServiceRoles.validName(manifest.archetype(), name)) {
      throw new IllegalArgumentException("Invalid deployment name for " + manifest.archetype());
    }
    dir = dir.toAbsolutePath().normalize();
    List<String> existingProblems = problems(dir);
    if (!existingProblems.isEmpty()) throw new IOException(String.join("; ", existingProblems));
    ProjectLayout layout = ProjectLayout.read(dir, manifest);
    String resources = layout.relative("src/main/resources") + "/";
    Map<String, String> edits = new LinkedHashMap<>();
    Map<String, Object> ledger = readMap(SafePaths.target(dir, LEDGER));
    Map<String, Object> tracked =
        ledger.get("files") instanceof Map<?, ?> previous
            ? stringMap(previous)
            : new LinkedHashMap<>();
    String application =
        resources
            + (Files.isRegularFile(SafePaths.target(dir, resources + "application.yaml"))
                ? "application.yaml"
                : "application.yml");
    if (!Files.isRegularFile(SafePaths.target(dir, application))) {
      throw new IOException("--rename requires a base application.yaml or application.yml");
    }
    Map<String, Object> base = flat(SafePaths.target(dir, application));
    String old = String.valueOf(base.getOrDefault("spring.application.name", manifest.name()));
    if (old.contains("${"))
      throw new IOException("Resolve spring.application.name before renaming");
    Map<String, Object> defaults =
        Map.of(
            STABLE.get(0),
            "urn:axiz:" + old,
            STABLE.get(1),
            old,
            STABLE.get(2),
            old,
            STABLE.get(3),
            old);
    try (Stream<Path> paths = Files.walk(layout.target("src/main/resources"))) {
      for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
        String file = SafePaths.fileName(path);
        if (file.startsWith("application") && file.endsWith(".properties")) {
          throw new IOException("Convert application*.properties to YAML before --rename: " + file);
        }
        if (!file.startsWith("application") || !(file.endsWith(".yaml") || file.endsWith(".yml")))
          continue;
        SafePaths.target(dir, dir.relativize(path).toString());
        Map<String, Object> current = flat(path);
        String profileOld = String.valueOf(current.getOrDefault("spring.application.name", old));
        if (profileOld.contains("${"))
          throw new IOException("Resolve spring.application.name before renaming: " + file);
        Map<String, Object> identities = new LinkedHashMap<>();
        for (String key : STABLE) {
          Object value = current.get(key);
          if (value != null
              || path.equals(SafePaths.target(dir, application))
              || !profileOld.equals(old)) {
            String inherited = key.equals(STABLE.get(0)) ? "urn:axiz:" + profileOld : profileOld;
            Object fallback = profileOld.equals(old) ? defaults.get(key) : inherited;
            identities.put(
                key,
                String.valueOf(value == null ? fallback : value)
                    .replace("${spring.application.name}", profileOld));
          }
        }
        Map<String, Object> changes = new LinkedHashMap<>(identities);
        if (current.containsKey("spring.application.name")
            || path.equals(SafePaths.target(dir, application))) {
          changes.put("spring.application.name", name);
        }
        audienceAliases(current, changes, old, name);
        String relative = dir.relativize(path).toString();
        tracked.put(relative, identities);
        edits.put(relative, YamlProperties.merge(Files.readString(path), changes));
      }
    }
    for (Path chart : layout.charts()) {
      SafePaths.target(dir, dir.relativize(chart).toString());
      Path values = SafePaths.target(dir, dir.relativize(chart.resolve("values.yaml")).toString());
      if (!Files.isRegularFile(values)) continue;
      Path metadata = SafePaths.target(dir, dir.relativize(chart.resolve("Chart.yaml")).toString());
      if (Files.isRegularFile(metadata)) {
        edits.put(
            dir.relativize(metadata).toString(),
            YamlProperties.merge(Files.readString(metadata), Map.of("name", name)));
      }
      Map<String, Object> current = flat(values);
      List<String> aliases = new ArrayList<>();
      Object previous = current.get("migration.serviceAliases");
      if (previous instanceof List<?> list) list.forEach(v -> aliases.add(String.valueOf(v)));
      if (!aliases.contains(old)) aliases.add(old);
      aliases.remove(name);
      Map<String, Object> changes = new LinkedHashMap<>();
      changes.put("nameOverride", name);
      changes.put("migration.serviceAliases", aliases);
      changes.put("migration.previousAudience", old);
      changes.put("migration.currentAudience", name);
      // Chart overrides take precedence over the packaged application configuration.
      Map<String, Object> identities = new LinkedHashMap<>();
      try (Stream<Path> valuesFiles = Files.list(chart)) {
        for (Path v :
            valuesFiles
                .filter(
                    p ->
                        (SafePaths.fileName(p).equals("values.yaml")
                            || (SafePaths.fileName(p).startsWith("values-")
                                && SafePaths.fileName(p).endsWith(".yaml"))))
                .toList()) {
          SafePaths.target(dir, dir.relativize(v).toString());
          Map<String, Object> config = flat(v);
          Map<String, Object> updates = new LinkedHashMap<>();
          for (String key : STABLE) {
            Object value = config.get("config." + key);
            if (value != null)
              updates.put(
                  "config." + key,
                  String.valueOf(value).replace("${spring.application.name}", old));
          }
          audienceAliases(config, updates, old, name);
          if (v.equals(values)) updates.putAll(changes);
          String relative = dir.relativize(v).toString();
          identities.clear();
          updates.forEach(
              (k, value) -> {
                if (k.startsWith("config.axiz.messaging.")
                    || k.equals("config.axiz.config.parameter-store.application"))
                  identities.put(k, value);
              });
          tracked.put(relative, new LinkedHashMap<>(identities));
          edits.put(relative, YamlProperties.merge(Files.readString(v), updates));
        }
      }
      edits.put(
          dir.relativize(chart.resolve("templates/migration-service-aliases.yaml")).toString(),
          ALIAS_TEMPLATE);
    }
    ledger.putIfAbsent("originalName", old);
    ledger.put("currentName", name);
    ledger.put("files", tracked);
    edits.put(LEDGER, YamlProperties.dump(ledger, 0));
    for (String relative : edits.keySet()) {
      Path file = SafePaths.target(dir, relative);
      if (Files.isDirectory(file))
        throw new IOException("Rename target is a directory: " + relative);
    }
    if (!dryRun) {
      for (var edit : edits.entrySet()) {
        SafePaths.write(dir, edit.getKey(), edit.getValue().getBytes(StandardCharsets.UTF_8), true);
      }
    }
  }

  /** Identity overrides removed or modified after a rename are a failing doctor check. */
  public static List<String> problems(Path dir) throws IOException {
    dir = dir.toAbsolutePath().normalize();
    Map<String, Object> ledger = readMap(SafePaths.target(dir, LEDGER));
    List<String> problems = new ArrayList<>();
    if (ledger.get("files") instanceof Map<?, ?> files) {
      for (var file : files.entrySet()) {
        Path path = SafePaths.target(dir, String.valueOf(file.getKey()));
        Map<String, Object> actual = flat(path);
        if (file.getValue() instanceof Map<?, ?> expected)
          expected.forEach(
              (key, value) -> {
                if (!value.equals(actual.get(String.valueOf(key)))) {
                  problems.add("Stable identity changed: " + file.getKey() + " : " + key);
                }
              });
      }
    }
    return problems;
  }

  private static void audienceAliases(
      Map<String, Object> current, Map<String, Object> updates, String old, String name) {
    current.forEach(
        (key, value) -> {
          if (!key.matches("(?:config\\.)?axiz\\.security\\.issuers\\[\\d+]\\.audiences")) return;
          if (!(value instanceof List<?> list)) return;
          List<String> audiences =
              new ArrayList<>(
                  list.stream()
                      .map(String::valueOf)
                      .map(a -> a.replace("${spring.application.name}", old))
                      .toList());
          if (audiences.contains(old) && !audiences.contains(name)) audiences.add(name);
          updates.put(key, audiences);
        });
  }

  private static Map<String, Object> flat(Path path) throws IOException {
    return YamlProperties.flatten(readMap(path));
  }

  private static Map<String, Object> readMap(Path path) throws IOException {
    if (!Files.isRegularFile(path)) return new LinkedHashMap<>();
    Object value = SafeYaml.create(1_000_000).load(Files.readString(path));
    if (!(value instanceof Map<?, ?> map)) throw new IOException("Expected YAML mapping: " + path);
    return stringMap(map);
  }

  private static Map<String, Object> stringMap(Map<?, ?> map) {
    Map<String, Object> copy = new LinkedHashMap<>();
    map.forEach((k, v) -> copy.put(String.valueOf(k), v));
    return copy;
  }

  private static final String ALIAS_TEMPLATE =
      """
      {{- range .Values.migration.serviceAliases }}
      ---
      apiVersion: v1
      kind: Service
      metadata:
        name: {{ . | quote }}
        labels:
          {{- include "service.labels" $ | nindent 4 }}
      spec:
        type: ClusterIP
        ports:
          - name: http
            port: {{ $.Values.service.port }}
            targetPort: http
            protocol: TCP
        selector:
          {{- include "service.selectorLabels" $ | nindent 4 }}
      {{- end }}
      """;
}
