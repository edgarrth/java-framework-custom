package pe.axiz.demo.coverage;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The demo must show every capability of the axiz catalog: each one needs an entry in
 * coverage.yaml, declared by the service or executable example and evidenced in its code or
 * configuration. A new capability in the catalog fails this test until the demo uses it.
 */
class CapabilityCoverageTest {

  static final Path DEMO = Path.of("..").toAbsolutePath().normalize();

  static Path project(Map<String, String> use) {
    String service = use.get("service");
    String example = use.get("example");
    assertTrue(
        (service == null) != (example == null), "Choose exactly one service or executable example");
    String name = service == null ? example : service;
    assertTrue(name.matches("[a-z][a-z0-9-]*"), "Invalid example/service name");
    return DEMO.resolve(service == null ? "examples" : "services").resolve(name);
  }

  static Object yaml(InputStream in) {
    return new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
  }

  @SuppressWarnings("unchecked")
  static List<String> catalog() throws IOException {
    try (InputStream in =
        CapabilityCoverageTest.class.getResourceAsStream("/axiz/archetypes/capabilities.yaml")) {
      Map<String, Object> doc = (Map<String, Object>) yaml(Objects.requireNonNull(in, "catalog"));
      List<String> names = new ArrayList<>();
      for (Object c : (List<Object>) doc.get("capabilities")) {
        names.add(String.valueOf(((Map<String, Object>) c).get("name")));
      }
      return names;
    }
  }

  @SuppressWarnings("unchecked")
  static Map<String, List<Map<String, String>>> coverage() throws IOException {
    try (InputStream in = CapabilityCoverageTest.class.getResourceAsStream("/coverage.yaml")) {
      return (Map<String, List<Map<String, String>>>) yaml(Objects.requireNonNull(in, "coverage"));
    }
  }

  @SuppressWarnings("unchecked")
  static List<String> declared(Path project, boolean example) throws IOException {
    try (InputStream in =
        Files.newInputStream(
            project.resolve(example ? "capabilities.yaml" : ".axiz/project.yaml"))) {
      return (List<String>) ((Map<String, Object>) yaml(in)).get("capabilities");
    }
  }

  @Test
  void everyCatalogCapabilityIsUsedByTheDemo() throws IOException {
    Map<String, List<Map<String, String>>> coverage = coverage();
    List<String> problems = new ArrayList<>();
    for (String capability : catalog()) {
      List<Map<String, String>> uses = coverage.get(capability);
      if (uses == null || uses.isEmpty()) {
        problems.add(capability + ": no use in the demo");
        continue;
      }
      for (Map<String, String> use : uses) {
        Path project = project(use);
        Path file = project.resolve(use.get("file")).normalize();
        assertTrue(file.startsWith(project), "Capability evidence cannot leave its project");
        assertTrue(
            Files.isRegularFile(project.resolve("pom.xml")),
            "Evidence must belong to a Maven module");
        String module = DEMO.relativize(project).toString().replace('\\', '/');
        assertTrue(
            Files.readString(DEMO.resolve("pom.xml")).contains("<module>" + module + "</module>"),
            "Every demonstrated module must execute in the normal demo reactor");
        if (!declared(project, use.containsKey("example")).contains(capability)) {
          problems.add(capability + ": not declared by " + project);
        } else if (!Files.isRegularFile(file)) {
          problems.add(capability + ": missing " + file);
        } else if (!Files.readString(file, StandardCharsets.UTF_8).contains(use.get("contains"))) {
          problems.add(capability + ": " + file + " no longer contains " + use.get("contains"));
        }
      }
    }
    assertTrue(problems.isEmpty(), String.join("\n", problems));
  }

  @Test
  void coverageOnlyNamesCatalogCapabilities() throws IOException {
    List<String> catalog = catalog();
    List<String> unknown = coverage().keySet().stream().filter(c -> !catalog.contains(c)).toList();
    assertTrue(unknown.isEmpty(), "unknown capabilities: " + unknown);
  }
}
