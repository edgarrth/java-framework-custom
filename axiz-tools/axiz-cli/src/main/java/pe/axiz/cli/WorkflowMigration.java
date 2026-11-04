package pe.axiz.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import pe.axiz.codegen.openapi.SafeYaml;
import pe.axiz.codegen.output.SafePaths;
import pe.axiz.codegen.project.MavenReactor;
import pe.axiz.codegen.project.ProjectLayout;
import pe.axiz.codegen.project.ProjectManifest;
import pe.axiz.codegen.project.ProjectRename;
import pe.axiz.codegen.render.TemplateSet;

/** Migrates known generated workflows; custom graphs remain a proposal in the isolated copy. */
final class WorkflowMigration {
  private static final Pattern LEGACY =
      Pattern.compile(
          "axiz/axiz-workflows/\\.github/workflows/(?:lib-publish|lib-promote|service-ci|service-cd)\\.yaml@");
  private static final String CI = ".github/workflows/ci.yaml";
  private static final String CD = ".github/workflows/cd.yaml";
  private static final String DEPLOY = ".github/workflows/deploy.yaml";
  private static final String RELEASE = ".github/workflows/release.yaml";

  private WorkflowMigration() {}

  static boolean required(Path directory) throws IOException {
    Path workflows = SafePaths.target(directory, ".github/workflows");
    if (!Files.isDirectory(workflows)) return false;
    try (Stream<Path> files = Files.list(workflows)) {
      for (Path file :
          files
              .filter(path -> path.toString().endsWith(".yaml") || path.toString().endsWith(".yml"))
              .toList()) {
        SafePaths.target(directory, directory.relativize(file).toString());
        if (Files.isRegularFile(file) && LEGACY.matcher(Files.readString(file)).find()) return true;
      }
    }
    return false;
  }

  /** Returns false when a proposed replacement would discard customized workflow behavior. */
  static boolean migrate(Path directory, ProjectManifest before, ProjectManifest after)
      throws IOException {
    if (!required(directory)) return renameCurrentBindings(directory, before, after);
    String set = before.isService() ? "service" : "library";
    TemplateSet templates = TemplateSet.load(set);
    String originalName = originalName(directory, before);
    Map<String, Object> oldContext = context(before, originalName);
    Map<String, Object> newContext = new LinkedHashMap<>(context(after, after.name()));
    List<Path> charts = List.of();
    boolean standardServicePom = true;
    if (after.isService()) {
      ProjectLayout layout = ProjectLayout.read(directory, after);
      // The reusable service build contract selects <module>/pom.xml.
      standardServicePom = SafePaths.fileName(layout.pom()).equals("pom.xml");
      charts = layout.charts();
      String module =
          directory
              .toAbsolutePath()
              .normalize()
              .relativize(layout.module())
              .toString()
              .replace('\\', '/');
      String pom =
          directory
              .toAbsolutePath()
              .normalize()
              .relativize(layout.pom())
              .toString()
              .replace('\\', '/');
      String chart =
          charts.size() == 1
              ? directory
                  .toAbsolutePath()
                  .normalize()
                  .relativize(charts.getFirst())
                  .toString()
                  .replace('\\', '/')
              : "deploy/helm/" + originalName;
      newContext.put(
          "project",
          Map.of(
              "name",
              after.name(),
              "moduleYaml",
              JsonWriter.write(module.isEmpty() ? "." : module),
              "pomYaml",
              JsonWriter.write(pom),
              "chartYaml",
              JsonWriter.write(chart)));
    }
    String oldCi = templates.renderTemplate("/migration/v1/" + set + "-ci.fixture", oldContext);
    String newCi =
        templates.renderTemplate(
            before.isService() ? "github/ci.yaml.mustache" : "ci.yaml.mustache", newContext);
    boolean safe = standardServicePom && same(directory, CI, oldCi);
    if (before.isService()) {
      safe &=
          same(
              directory,
              CD,
              templates.renderTemplate("/migration/v1/service-cd.fixture", oldContext));
      // Charts retain their directories across renames, including charts inside the service module.
      if (charts.size() != 1) safe = false;
    }
    Path existingDeploy = SafePaths.target(directory, DEPLOY);
    if (Files.exists(existingDeploy)) safe = false;
    String oldRelease = resource("migration/v1/release.fixture");
    if (Files.exists(SafePaths.target(directory, RELEASE)) && !same(directory, RELEASE, oldRelease))
      safe = false;
    // Unknown files invoking an old contract also require review; migrating ci alone is
    // insufficient.
    Path workflows = SafePaths.target(directory, ".github/workflows");
    try (Stream<Path> files = Files.list(workflows)) {
      for (Path path :
          files
              .filter(Files::isRegularFile)
              .filter(path -> path.toString().endsWith(".yaml") || path.toString().endsWith(".yml"))
              .toList()) {
        String name = directory.relativize(path).toString().replace('\\', '/');
        if (!List.of(CI, CD, RELEASE).contains(name)
            && LEGACY.matcher(Files.readString(path)).find()) safe = false;
      }
    }
    SafePaths.write(directory, CI, newCi.getBytes(StandardCharsets.UTF_8), true);
    SafePaths.write(
        directory,
        DEPLOY,
        templates
            .renderTemplate(
                before.isService() ? "github/deploy.yaml.mustache" : "deploy.yaml.mustache",
                newContext)
            .getBytes(StandardCharsets.UTF_8),
        true);
    if (before.isService()) Files.deleteIfExists(SafePaths.target(directory, CD));
    SafePaths.write(
        directory,
        RELEASE,
        resource("shared/release/release.yaml").getBytes(StandardCharsets.UTF_8),
        true);
    releaseConfiguration(directory, after);
    // A project already renamed by an older CLI may retain the original chart directory.
    // Repair through the same identity-preserving rename operation, not a separate identity policy.
    if (after.isService()
        && Files.isRegularFile(SafePaths.target(directory, ProjectRename.LEDGER))) {
      ProjectRename.apply(directory, after, after.name(), false);
    }
    return safe;
  }

  private static String originalName(Path directory, ProjectManifest manifest) throws IOException {
    Path ledger = SafePaths.target(directory, ProjectRename.LEDGER);
    if (!Files.isRegularFile(ledger)) return manifest.name();
    Object parsed = yaml(Files.readString(ledger));
    if (parsed instanceof Map<?, ?> values && values.get("originalName") instanceof String name) {
      SafePaths.segment(name);
      return name;
    }
    return manifest.name();
  }

  private static boolean renameCurrentBindings(
      Path directory, ProjectManifest before, ProjectManifest after) throws IOException {
    if (!after.isService() || before.name().equals(after.name())) return true;
    Path workflows = SafePaths.target(directory, ".github/workflows");
    if (!Files.isDirectory(workflows)) return true;
    Map<String, byte[]> edits = new LinkedHashMap<>();
    Pattern binding =
        Pattern.compile(
            "(?m)^(\\h*service:\\h*)(['\"]?)"
                + Pattern.quote(before.name())
                + "\\2(?=\\h*(?:#.*)?$)");
    try (Stream<Path> paths = Files.list(workflows)) {
      for (Path path : paths.sorted().toList()) {
        if (!(path.toString().endsWith(".yaml") || path.toString().endsWith(".yml"))) continue;
        String relative = directory.relativize(path).toString();
        SafePaths.target(directory, relative);
        String source = Files.readString(path);
        Object expected = yaml(source);
        if (!renameBindings(expected, before.name(), after.name())) continue;
        String proposed = binding.matcher(source).replaceAll("$1$2" + after.name() + "$2");
        // Preserve comments and the custom graph. A flow-style or unrelated same-name field
        // requires manual review rather than an unverified textual replacement.
        if (!yaml(proposed).equals(expected)) return false;
        edits.put(relative, proposed.getBytes(StandardCharsets.UTF_8));
      }
    }
    for (var edit : edits.entrySet())
      SafePaths.write(directory, edit.getKey(), edit.getValue(), true);
    return true;
  }

  @SuppressWarnings("unchecked")
  private static boolean renameBindings(Object node, String before, String after) {
    boolean changed = false;
    if (node instanceof Map<?, ?> map) {
      Object reference = map.get("uses");
      if (reference instanceof String ref
          && (ref.startsWith("axiz/axiz-workflows/.github/workflows/service-build.yaml@v2.")
              || ref.startsWith("axiz/axiz-workflows/actions/resolve-service-build@v2."))
          && map.get("with") instanceof Map<?, ?> parameters
          && before.equals(parameters.get("service"))) {
        ((Map<Object, Object>) parameters).put("service", after);
        changed = true;
      }
      for (Object value : map.values()) changed |= renameBindings(value, before, after);
    } else if (node instanceof List<?> list) {
      for (Object value : list) changed |= renameBindings(value, before, after);
    }
    return changed;
  }

  private static Map<String, Object> context(ProjectManifest manifest, String name) {
    return Map.of(
        "project",
        Map.of("name", name),
        "axizVersion",
        manifest.axizVersion(),
        "api",
        Map.of("present", manifest.openapi() != null, "hasAsyncapi", manifest.asyncapi() != null));
  }

  private static boolean same(Path directory, String name, String expected) throws IOException {
    Path file = SafePaths.target(directory, name);
    return Files.isRegularFile(file) && yaml(Files.readString(file)).equals(yaml(expected));
  }

  private static Object yaml(String text) {
    Object value = SafeYaml.create(1_000_000).load(text);
    if (!(value instanceof Map<?, ?>))
      throw new IllegalArgumentException("Workflow must be a YAML mapping");
    return value;
  }

  private static String resource(String name) throws IOException {
    try (InputStream input =
        WorkflowMigration.class.getResourceAsStream("/axiz/templates/" + name)) {
      if (input == null) throw new IOException("Missing workflow migration resource " + name);
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static void releaseConfiguration(Path directory, ProjectManifest manifest)
      throws IOException {
    Path path = SafePaths.target(directory, "release-please-config.json");
    if (!Files.isRegularFile(path)) return;
    Object parsed = SafeYaml.create(1_000_000).load(Files.readString(path));
    if (!(parsed instanceof Map<?, ?> values))
      throw new IOException("Invalid release-please configuration");
    Map<String, Object> changed = new LinkedHashMap<>();
    for (var entry : values.entrySet()) {
      if (!(entry.getKey() instanceof String key))
        throw new IOException("Release configuration keys must be strings");
      changed.put(key, entry.getValue());
    }
    if (!manifest.isService()) changed.put("draft", true);
    changed.put("force-tag-creation", true);
    Object extras = changed.get("extra-files");
    List<Object> files = new java.util.ArrayList<>();
    if (extras instanceof List<?> list) files.addAll(list);
    else if (extras != null) throw new IOException("release-please extra-files must be a list");
    List<Path> poms =
        manifest.isService()
            ? List.of(ProjectLayout.read(directory, manifest).pom())
            : MavenReactor.read(directory).stream().map(MavenReactor.Module::pom).toList();
    for (Path pom : poms) {
      String relative =
          directory.toAbsolutePath().normalize().relativize(pom).toString().replace('\\', '/');
      boolean configured =
          files.stream()
              .anyMatch(
                  file ->
                      relative.equals(file)
                          || file instanceof Map<?, ?> settings
                              && relative.equals(settings.get("path")));
      if (!configured)
        files.add(
            Map.of(
                "type",
                "xml",
                "path",
                relative,
                "xpath",
                "/*[local-name()='project']/*[local-name()='version']"));
    }
    changed.put("extra-files", files);
    SafePaths.write(
        directory,
        "release-please-config.json",
        (JsonWriter.write(changed) + "\n").getBytes(StandardCharsets.UTF_8),
        true);
  }
}
