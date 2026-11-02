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
 * coverage.yaml, declared by the service in .axiz/project.yaml and evidenced in its code or
 * configuration. A new capability in the catalog fails this test until the demo uses it.
 */
class CapabilityCoverageTest {

  static final Path SERVICES = Path.of("..", "services");

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
  static List<String> declared(String service) throws IOException {
    try (InputStream in =
        Files.newInputStream(SERVICES.resolve(service).resolve(".axiz/project.yaml"))) {
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
        String service = use.get("service");
        Path file = SERVICES.resolve(service).resolve(use.get("file"));
        if (!declared(service).contains(capability)) {
          problems.add(capability + ": not declared by " + service);
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
