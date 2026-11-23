package pe.axiz.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import pe.axiz.codegen.openapi.SafeYaml;
import pe.axiz.codegen.output.SafePaths;
import pe.axiz.codegen.project.ProjectLayout;
import pe.axiz.codegen.project.ProjectManifest;
import pe.axiz.core.json.JsonWriter;

/**
 * Actualiza de forma segura los bindings vigentes de los workflows cuando se renombra un servicio.
 */
final class WorkflowBindings {
  private WorkflowBindings() {}

  static boolean required(Path directory, ProjectManifest manifest) throws IOException {
    if (!manifest.isService()) return false;
    var layout = ProjectLayout.read(directory, manifest);
    if (!SafePaths.fileName(layout.pom()).equals("pom.xml")) return true;
    Path ci = SafePaths.target(directory, ".github/workflows/ci.yaml");
    if (!Files.isRegularFile(ci)) return false;
    Map<String, String> values = bindings(directory, manifest);
    return alignBindings(yaml(Files.readString(ci)), values, new LinkedHashMap<>());
  }

  private static Map<String, String> bindings(Path directory, ProjectManifest manifest)
      throws IOException {
    var layout = ProjectLayout.read(directory, manifest);
    var root = directory.toAbsolutePath().normalize();
    String module = root.relativize(layout.module()).toString().replace('\\', '/');
    var result = new LinkedHashMap<String, String>();
    result.put("service", manifest.name());
    result.put("module", module.isEmpty() ? "." : module);
    result.put("pom", root.relativize(layout.pom()).toString().replace('\\', '/'));
    if (layout.charts().size() == 1)
      result.put(
          "chart", root.relativize(layout.charts().getFirst()).toString().replace('\\', '/'));
    return result;
  }

  static boolean rename(Path directory, ProjectManifest before, ProjectManifest after)
      throws IOException {
    if (!after.isService()) return true;
    var layout = ProjectLayout.read(directory, after);
    if (!SafePaths.fileName(layout.pom()).equals("pom.xml")) return false;
    Map<String, String> values = bindings(directory, after);
    Path workflows = SafePaths.target(directory, ".github/workflows");
    if (!Files.isDirectory(workflows)) return true;
    Map<String, byte[]> edits = new LinkedHashMap<>();
    try (Stream<Path> paths = Files.list(workflows)) {
      for (Path path : paths.sorted().toList()) {
        if (!(path.toString().endsWith(".yaml") || path.toString().endsWith(".yml"))) continue;
        String relative = directory.relativize(path).toString();
        SafePaths.target(directory, relative);
        String source = Files.readString(path);
        Object expected = yaml(source);
        Map<String, String> previous = new LinkedHashMap<>();
        if (!alignBindings(expected, values, previous)) continue;
        String proposed = source;
        for (var value : previous.entrySet()) {
          Pattern binding =
              Pattern.compile(
                  "(?m)^(\\h*"
                      + value.getKey()
                      + ":\\h*)(['\"]?)"
                      + Pattern.quote(value.getValue())
                      + "\\2(?=\\h*(?:#.*)?$)");
          proposed =
              binding.matcher(proposed).replaceAll("$1$2" + values.get(value.getKey()) + "$2");
        }
        // Se preservan los comentarios y el grafo personalizado. Un campo en estilo flow o un campo
        // no relacionado con el mismo nombre requiere revisión manual en lugar de un reemplazo
        // textual sin verificar.
        if (!yaml(proposed).equals(expected)) return false;
        edits.put(relative, proposed.getBytes(StandardCharsets.UTF_8));
      }
    }
    for (var edit : edits.entrySet())
      SafePaths.write(directory, edit.getKey(), edit.getValue(), true);
    Path release = SafePaths.target(directory, "release-please-config.json");
    if (Files.isRegularFile(release)) {
      Object config = SafeYaml.create(1_000_000).load(Files.readString(release));
      if (!(config instanceof Map<?, ?> mapping))
        throw new IOException("Invalid release configuration");
      Map<String, Object> copy = new LinkedHashMap<>();
      mapping.forEach((key, value) -> copy.put(String.valueOf(key), value));
      var files = new ArrayList<Object>();
      if (copy.get("extra-files") instanceof List<?> existing) files.addAll(existing);
      String releasePom = Objects.requireNonNull(values.get("pom"));
      boolean configured =
          files.stream()
              .anyMatch(
                  file ->
                      releasePom.equals(file)
                          || (file instanceof Map<?, ?> item
                              && releasePom.equals(item.get("path"))));
      if (!configured)
        files.add(
            Map.of(
                "type",
                "xml",
                "path",
                values.get("pom"),
                "xpath",
                "/*[local-name()='project']/*[local-name()='version']"));
      copy.put("extra-files", files);
      SafePaths.write(
          directory,
          "release-please-config.json",
          (JsonWriter.write(copy) + "\n").getBytes(StandardCharsets.UTF_8),
          true);
    }
    return true;
  }

  @SuppressWarnings("unchecked")
  private static boolean alignBindings(
      @Nullable Object node, Map<String, String> values, Map<String, String> previous) {
    boolean changed = false;
    if (node instanceof Map<?, ?> map) {
      Object reference = map.get("uses");
      if (reference instanceof String ref
          && (ref.startsWith(
                  "edgarrth/java-framework-custom/.github/workflows/service-build.yaml@axiz-workflows-v2.")
              || ref.startsWith(
                  "edgarrth/java-framework-custom/.github/workflows/service-deploy.yaml@axiz-workflows-v2."))
          && map.get("with") instanceof Map<?, ?> parameters) {
        // service-deploy solo recibe el nombre del servicio: imagen, chart y versión son salidas
        // del build o valores derivados del nombre, no rutas del proyecto.
        boolean deploy = ref.contains("/service-deploy.yaml@");
        for (var value : values.entrySet()) {
          if (deploy && !"service".equals(value.getKey())) continue;
          Object old = parameters.get(value.getKey());
          if (old instanceof String oldValue && !oldValue.equals(value.getValue())) {
            String conflict = previous.putIfAbsent(value.getKey(), oldValue);
            if (conflict != null && !conflict.equals(oldValue))
              throw new IllegalArgumentException("Conflicting workflow bindings");
            ((Map<Object, Object>) parameters).put(value.getKey(), value.getValue());
            changed = true;
          }
        }
      }
      for (Object value : map.values()) changed |= alignBindings(value, values, previous);
    } else if (node instanceof List<?> list) {
      for (Object value : list) changed |= alignBindings(value, values, previous);
    }
    return changed;
  }

  private static Object yaml(String text) {
    Object value = SafeYaml.create(1_000_000).load(text);
    if (!(value instanceof Map<?, ?>))
      throw new IllegalArgumentException("Workflow must be a YAML mapping");
    return value;
  }
}
