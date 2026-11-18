package pe.axiz.quality.architecture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

/** Uses real Maven module namespaces, including independently packaged integrations. */
final class FrameworkModules {
  private record Module(String namespace, boolean neutral) {}

  private static final List<Module> MODULES = read();

  private FrameworkModules() {}

  static String module(String packageName) {
    return MODULES.stream()
        .filter(
            m -> packageName.equals(m.namespace()) || packageName.startsWith(m.namespace() + "."))
        .map(Module::namespace)
        .findFirst()
        .orElseGet(() -> fallback(packageName));
  }

  static boolean neutral(String packageName) {
    String name = module(packageName);
    return MODULES.stream().anyMatch(m -> m.namespace().equals(name) && m.neutral());
  }

  static boolean allowedSpringDependency(String source, String target) {
    String own = module(source);
    String other = module(target);
    if (own.equals(other) || other.equals("pe.axiz.spring.foundation")) return true;
    String capability =
        own.startsWith("pe.axiz.spring.tokenization.vault")
            ? "tokenization.vault"
            : own.substring("pe.axiz.spring.".length()).split("\\.")[0];
    // An integration can depend on its base; the inverse and sibling dependencies are forbidden.
    if (!own.equals("pe.axiz.spring." + capability) && other.equals("pe.axiz.spring." + capability))
      return true;
    if (other.equals("pe.axiz.spring.aws"))
      return own.equals("pe.axiz.spring.dynamodb")
          || own.equals("pe.axiz.spring.tokenization.vault.dynamodb")
          || !neutral(own)
              && own.matches("pe\\.axiz\\.spring\\.[^.]+\\.(aws|dynamodb|s3|sqs|sns|kms|msk)");
    if (other.equals("pe.axiz.spring.kafka"))
      return Set.of("pe.axiz.spring.audit.kafka", "pe.axiz.spring.messaging.kafka").contains(own);
    if (other.equals("pe.axiz.spring.messaging.kafka"))
      return own.equals("pe.axiz.spring.messaging.msk");
    if (other.equals("pe.axiz.spring.httpclient")) return own.equals("pe.axiz.spring.tokenization");
    if (other.equals("pe.axiz.spring.sensitive")) return own.equals("pe.axiz.spring.httpclient");
    if (other.equals("pe.axiz.spring.jdbc"))
      return Set.of("pe.axiz.spring.jpa", "pe.axiz.spring.r2dbc").contains(own);
    return false;
  }

  private static String fallback(String packageName) {
    if (!packageName.startsWith("pe.axiz.")) return packageName;
    String[] parts = packageName.substring("pe.axiz.".length()).split("\\.");
    if (parts.length < 2) return packageName;
    return "pe.axiz."
        + parts[0]
        + "."
        + parts[1]
        + (parts[0].equals("adapter") && parts.length > 2 ? "." + parts[2] : "");
  }

  private static List<Module> read() {
    try {
      Path root =
          Path.of(
              FrameworkModules.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      while (root != null
          && !Files.isRegularFile(root.resolve("platform/axiz-parent-core/pom.xml")))
        root = root.getParent();
      if (root == null) throw new IllegalStateException("Cannot locate framework module inventory");
      var factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      var modules = new java.util.ArrayList<Module>();
      try (var files = Files.walk(root)) {
        for (Path pom :
            files
                .filter(p -> p.getFileName().toString().equals("pom.xml"))
                .filter(p -> !p.toString().contains("/target/") && !p.toString().contains("/src/"))
                .toList()) {
          var project = factory.newDocumentBuilder().parse(pom.toFile());
          var names = project.getElementsByTagName("axiz.module.name");
          if (names.getLength() != 1) continue;
          var policy = project.getElementsByTagName("axiz.provider.boundaries.skip");
          modules.add(
              new Module(
                  names.item(0).getTextContent().strip(),
                  policy.getLength() == 1
                      && policy.item(0).getTextContent().strip().equals("false")));
        }
      }
      if (modules.stream().map(Module::namespace).distinct().count() != modules.size())
        throw new IllegalStateException("Framework module namespaces must be unique");
      return modules.stream()
          .sorted(Comparator.comparingInt((Module m) -> m.namespace().length()).reversed())
          .toList();
    } catch (Exception failure) {
      throw new IllegalStateException("Cannot read framework module boundaries", failure);
    }
  }
}
