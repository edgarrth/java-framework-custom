package pe.axiz.demo.coverage;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** Cross-checks test execution against resolved dependencies and the framework checkout. */
final class RuntimeEvidence {
  private static final Map<String, Path> MODULES = new HashMap<>();

  private RuntimeEvidence() {}

  static void validate(
      Element executed, String capability, List<String> variants, Map<String, Object> catalog)
      throws Exception {
    Path report = Path.of(URI.create(executed.getOwnerDocument().getDocumentURI()));
    Path events = report.resolveSibling("open-test-report.xml");
    validateConsumerFreshness(parent(parent(parent(report))), events);
    Set<String> reported =
        OpenTestEvidence.variants(
            events,
            executed.getAttribute("classname"),
            executed.getAttribute("name").split("\\(", 2)[0],
            capability);
    assertTrue(
        reported.containsAll(variants),
        "The executed operation did not demonstrate " + capability + " variants " + variants);
    Set<String> required = new HashSet<>();
    if (catalog.get("starter") instanceof String starter) required.add(starter);
    addDependencies(required, catalog.get("dependencies"));
    if (catalog.get("persistence") instanceof Map<?, ?> persistence) {
      for (String variant : variants) {
        if (persistence.get(variant) instanceof Map<?, ?> provider)
          addDependencies(required, provider.get("dependencies"));
      }
    }
    List<Path> classpath = new ArrayList<>();
    var globals = executed.getOwnerDocument().getElementsByTagName("property");
    for (int i = 0; i < globals.getLength(); i++) {
      var property = (Element) globals.item(i);
      if (property.getAttribute("name").equals("java.class.path")) {
        for (String item : property.getAttribute("value").split(Pattern.quote(File.pathSeparator)))
          classpath.add(Path.of(item));
      }
    }
    assertTrue(!classpath.isEmpty(), "Execution report must retain its actual runtime classpath");
    for (String artifact : required) {
      assertTrue(
          classpath.stream()
              .anyMatch(path -> fileName(path).matches(Pattern.quote(artifact) + "-[0-9].*\\.jar")),
          capability + " has no resolved catalog artifact: " + artifact);
    }
    validateFreshness(classpath, report);
    validateFreshness(classpath, events);
  }

  private static void addDependencies(Set<String> required, Object dependencies) {
    if (dependencies instanceof List<?> list) {
      for (Object dependency : list) {
        String[] coordinates = dependency.toString().split(":");
        if (coordinates.length == 2 && coordinates[0].equals("pe.axiz"))
          required.add(coordinates[1]);
      }
    }
  }

  static void validateConsumerFreshness(Path project, Path report) throws Exception {
    assertTrue(Files.isRegularFile(report), "Missing execution evidence: " + report);
    long newest = Files.getLastModifiedTime(project.resolve("pom.xml")).toMillis();
    try (var files = Files.walk(project.resolve("src"))) {
      for (Path file : files.filter(Files::isRegularFile).toList())
        newest = Math.max(newest, Files.getLastModifiedTime(file).toMillis());
    }
    assertTrue(
        Files.getLastModifiedTime(report).toMillis() >= newest,
        "Stale execution after source/POM changes: " + report);
  }

  static void validateFreshness(List<Path> classpath, Path report) throws Exception {
    discoverModules();
    long executedAt = Files.getLastModifiedTime(report).toMillis();
    for (Path artifact : classpath) {
      if (!artifact.toString().replace('\\', '/').contains("/pe/axiz/")) continue;
      assertTrue(
          Files.isRegularFile(artifact),
          "Resolved framework artifact no longer exists: " + artifact);
      long builtAt = Files.getLastModifiedTime(artifact).toMillis();
      assertTrue(executedAt >= builtAt, "Re-run demo after rebuilding " + artifact);
      Path module = MODULES.get(fileName(parent(parent(artifact))));
      if (module == null) continue; // A published dependency without an adjacent source checkout.
      long changed = Files.getLastModifiedTime(module.resolve("pom.xml")).toMillis();
      Path sources = module.resolve("src/main");
      if (Files.isDirectory(sources)) {
        try (var files = Files.walk(sources)) {
          for (Path file : files.filter(Files::isRegularFile).toList())
            changed = Math.max(changed, Files.getLastModifiedTime(file).toMillis());
        }
      }
      assertTrue(builtAt >= changed, "Install current framework sources before demo: " + module);
    }
  }

  private static Path parent(Path path) {
    return Objects.requireNonNull(path.getParent(), "Evidence path requires a parent directory");
  }

  private static String fileName(Path path) {
    return Objects.requireNonNull(path.getFileName(), "Evidence path requires a file name")
        .toString();
  }

  private static synchronized void discoverModules() throws Exception {
    if (!MODULES.isEmpty()) return;
    Path framework = CapabilityCoverageTest.DEMO.resolveSibling("axiz-framework");
    if (!Files.isDirectory(framework)) return;
    var factory = DocumentBuilderFactory.newDefaultInstance();
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    try (var files = Files.walk(framework)) {
      for (Path pom :
          files
              .filter(
                  path ->
                      path.endsWith("pom.xml")
                          && !path.toString().contains(File.separator + "target" + File.separator))
              .toList()) {
        var root = factory.newDocumentBuilder().parse(pom.toFile()).getDocumentElement();
        var children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
          if (children.item(i) instanceof Element element
              && element.getTagName().equals("artifactId"))
            MODULES.put(element.getTextContent().strip(), pom.getParent());
        }
      }
    }
  }
}
