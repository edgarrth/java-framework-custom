package pe.axiz.demo.coverage;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * La demo debe mostrar cada capacidad del catálogo de axiz: cada una necesita una entrada en
 * coverage.yaml, declarada por el servicio o el ejemplo ejecutable y respaldada por una operación
 * JUnit exitosa y vigente. Una dependencia, un comentario, un ajuste YAML o una prueba omitida no
 * cuentan como ejecución.
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
  void everyCatalogCapabilityIsUsedByTheDemo() throws Exception {
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
        if (use.containsKey("service")) {
          try (InputStream input = Files.newInputStream(project.resolve(".axiz/project.yaml"))) {
            var metadata = (Map<?, ?>) yaml(input);
            for (String variant : use.getOrDefault("variant", "default").split(","))
              if (Set.of("servlet", "reactive").contains(variant))
                assertTrue(
                    variant.equals(metadata.get("stack")),
                    "Service stack does not match claimed evidence");
          }
        }
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
        }
        String test =
            Objects.requireNonNull(use.get("test"), capability + ": missing executable test");
        assertTrue(
            Set.of("integration", "consumer-contract", "local-identity-contract")
                .contains(use.get("level")),
            capability + ": evidence must declare its actual scope");
        Element executed = validateExecution(project, test);
        RuntimeEvidence.validate(
            executed,
            capability,
            List.of(use.getOrDefault("variant", "default").split(",")),
            catalogDetails(capability));
      }
    }
    assertTrue(problems.isEmpty(), String.join("\n", problems));
  }

  static Element validateExecution(Path project, String test) throws Exception {
    String[] identity = test.split("#", -1);
    assertTrue(
        identity.length == 2
            && identity[0].matches("[A-Za-z0-9_$.]+")
            && identity[1].matches("[A-Za-z0-9_]+"),
        "Use fully.qualified.Class#method");
    var reports = new ArrayList<Path>();
    for (String plugin : List.of("surefire-reports", "failsafe-reports")) {
      Path candidate = project.resolve("target/" + plugin + "/TEST-" + identity[0] + ".xml");
      if (Files.isRegularFile(candidate)) reports.add(candidate);
    }
    assertTrue(
        reports.size() == 1, "Run the demo reactor: missing or ambiguous execution for " + test);
    Path report = reports.getFirst();
    RuntimeEvidence.validateConsumerFreshness(project, report);
    var factory = DocumentBuilderFactory.newDefaultInstance();
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    var document = factory.newDocumentBuilder().parse(report.toFile());
    var cases = document.getElementsByTagName("testcase");
    Element matched = null;
    for (int i = 0; i < cases.getLength(); i++) {
      var item = (Element) cases.item(i);
      if (!item.getAttribute("classname").equals(identity[0])) continue;
      String name = item.getAttribute("name");
      if (!(name.equals(identity[1]) || name.startsWith(identity[1] + "("))) continue;
      matched = item;
      for (String failure : List.of("failure", "error", "skipped"))
        assertTrue(
            item.getElementsByTagName(failure).getLength() == 0,
            "Capability evidence cannot be " + failure + ": " + test);
    }
    assertTrue(matched != null, "No executed test case for " + test);
    return Objects.requireNonNull(matched);
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> catalogDetails(String name) throws IOException {
    try (InputStream in =
        CapabilityCoverageTest.class.getResourceAsStream("/axiz/archetypes/capabilities.yaml")) {
      var doc = (Map<String, Object>) yaml(Objects.requireNonNull(in));
      return ((List<Map<String, Object>>) doc.get("capabilities"))
          .stream().filter(c -> name.equals(c.get("name"))).findFirst().orElseThrow();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void everyRequiredProviderVariantHasExecutedEvidence() throws Exception {
    Map<String, List<String>> variants;
    try (InputStream in = CapabilityCoverageTest.class.getResourceAsStream("/variants.yaml")) {
      variants = (Map<String, List<String>>) yaml(Objects.requireNonNull(in));
    }
    // El catálogo de referencia define los stacks y la persistencia; variants.yaml solo agrega
    // operaciones.
    try (InputStream in =
        CapabilityCoverageTest.class.getResourceAsStream("/axiz/archetypes/capabilities.yaml")) {
      var document = (Map<String, Object>) yaml(Objects.requireNonNull(in));
      for (var capability : (List<Map<String, Object>>) document.get("capabilities")) {
        var required =
            new HashSet<>(variants.getOrDefault(capability.get("name").toString(), List.of()));
        if (capability.get("persistence") instanceof Map<?, ?> persistence)
          persistence.keySet().forEach(name -> required.add(name.toString()));
        if (capability.get("stacks") instanceof List<?> stacks)
          stacks.forEach(name -> required.add(name.toString()));
        variants.put(capability.get("name").toString(), List.copyOf(required));
      }
    }
    var uses = coverage();
    for (var entry : variants.entrySet()) {
      assertTrue(catalog().contains(entry.getKey()), "Unknown variant capability");
      Set<String> actual = new HashSet<>();
      for (var use : uses.getOrDefault(entry.getKey(), List.of())) {
        if (use.containsKey("variant")) actual.addAll(List.of(use.get("variant").split(",")));
      }
      assertTrue(
          actual.containsAll(entry.getValue()),
          "Missing variants for " + entry.getKey() + ": " + entry.getValue());
    }
  }

  @Test
  void coverageOnlyNamesCatalogCapabilities() throws IOException {
    List<String> catalog = catalog();
    List<String> unknown = coverage().keySet().stream().filter(c -> !catalog.contains(c)).toList();
    assertTrue(unknown.isEmpty(), "unknown capabilities: " + unknown);
  }
}
