package pe.axiz.quality.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

class TechnologyDependenciesTest {
  @Test
  void productionPomDependenciesUseOnlyTheSameTechnologyKit() throws Exception {
    Path root = Path.of(System.getProperty("maven.multiModuleProjectDirectory"));
    var violations = new ArrayList<String>();
    var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    try (var files = Files.walk(root)) {
      for (Path pom :
          files
              .filter(p -> p.getFileName().toString().equals("pom.xml"))
              .filter(p -> !p.toString().contains("/target/") && !p.toString().contains("/src/"))
              .toList()) {
        var project = factory.newDocumentBuilder().parse(pom.toFile()).getDocumentElement();
        String artifact = child(project, "artifactId");
        if (!artifact.startsWith("axiz-kit-") && !artifact.startsWith("axiz-adapter-")) continue;
        String module =
            artifact.startsWith("axiz-kit-")
                ? "kit." + artifact.substring(9)
                : artifact.substring(13).replace('-', '.');
        String technology = artifact.substring(artifact.lastIndexOf('-') + 1);
        var dependencies = project.getElementsByTagName("dependency");
        for (int i = 0; i < dependencies.getLength(); i++) {
          Element dependency = (Element) dependencies.item(i);
          if (!(dependency.getParentNode() instanceof Element parent)
              || !parent.getTagName().equals("dependencies")
              || !(parent.getParentNode() instanceof Element owner)
              || !Set.of("project", "profile").contains(owner.getTagName())) continue;
          String target = child(dependency, "artifactId");
          if (!child(dependency, "groupId").equals("pe.axiz") || !target.startsWith("axiz-kit-"))
            continue;
          String scope = child(dependency, "scope");
          if (Set.of("", "compile", "runtime").contains(scope)
              && !TechnologyBoundaries.allowed(module, technology, target.substring(9)))
            violations.add(artifact + " -> " + target + " in " + pom);
        }
      }
    }
    assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
  }

  private static String child(Element element, String name) {
    for (var node = element.getFirstChild(); node != null; node = node.getNextSibling())
      if (node instanceof Element e && e.getTagName().equals(name))
        return e.getTextContent().strip();
    return "";
  }
}
