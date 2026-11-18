package pe.axiz.cli;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import pe.axiz.codegen.model.Catalog;
import pe.axiz.codegen.project.ProjectLayout;
import pe.axiz.codegen.project.ProjectManifest;

/**
 * Reconciles the selected service's provider dependencies without moving pure-core dependencies.
 */
final class CapabilityAdapters {
  private CapabilityAdapters() {}

  static boolean required(Path project, ProjectManifest manifest) throws IOException {
    if (!manifest.isService()) return false;
    String pom = Files.readString(ProjectLayout.read(project, manifest).pom());
    return !pom.equals(align(pom, manifest));
  }

  static void apply(Path project, ProjectManifest manifest) throws IOException {
    if (!manifest.isService()) return;
    Path pom = ProjectLayout.read(project, manifest).pom();
    String before = Files.readString(pom);
    String after = align(before, manifest);
    if (!before.equals(after)) Files.writeString(pom, after);
  }

  static Set<String> expected(ProjectManifest manifest) {
    var catalog = Catalog.load();
    String store = Catalog.store(manifest.persistence(), manifest.stack());
    Set<String> adapters = new LinkedHashSet<>();
    Set<String> capabilities = new LinkedHashSet<>(manifest.capabilities());
    for (String implied : catalog.style(manifest.structure()).capabilities())
      if (catalog.capability(implied).map(capability -> capability.supports(store)).orElse(false))
        capabilities.add(implied);
    for (var capability : catalog.resolve(List.copyOf(capabilities), store))
      for (String coordinate : capability.dependencies(store))
        if (coordinate.startsWith("pe.axiz:axiz-")
            && (coordinate.startsWith("pe.axiz:axiz-adapter-")
                || coordinate.endsWith("-spring-boot-starter")))
          adapters.add(coordinate.substring("pe.axiz:".length()));
    return adapters;
  }

  static String align(String pom, ProjectManifest manifest) throws IOException {
    Set<String> expected = expected(manifest);
    if (expected.isEmpty()) return pom;
    try {
      var factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      Document document =
          factory.newDocumentBuilder().parse(new InputSource(new StringReader(pom)));
      Element root = document.getDocumentElement();
      Element dependencies = child(root, "dependencies");
      boolean changed = false;
      if (dependencies == null) {
        dependencies = element(document, "dependencies", null);
        root.appendChild(dependencies);
        changed = true;
      }
      for (String artifact : expected) {
        Element dependency = null;
        for (Element candidate : children(dependencies))
          if (candidate.getLocalName().equals("dependency")
              && text(candidate, "groupId").equals("pe.axiz")
              && text(candidate, "artifactId").equals(artifact)
              && text(candidate, "classifier").isEmpty()) {
            dependency = candidate;
            break;
          }
        if (dependency == null) {
          dependency = element(document, "dependency", null);
          dependencies.appendChild(dependency);
          dependency.appendChild(element(document, "groupId", "pe.axiz"));
          dependency.appendChild(element(document, "artifactId", artifact));
          changed = true;
        }
        Element scope = child(dependency, "scope");
        String requiredScope = artifact.startsWith("axiz-adapter-") ? "runtime" : "compile";
        if (scope == null && requiredScope.equals("runtime")) {
          dependency.appendChild(element(document, "scope", "runtime"));
          changed = true;
        } else if (scope != null && !scope.getTextContent().strip().equals(requiredScope)) {
          scope.setTextContent(requiredScope);
          changed = true;
        }
      }
      if (!changed) return pom;
      removeWhitespace(root);
      var transformers = TransformerFactory.newInstance();
      transformers.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
      var transformer = transformers.newTransformer();
      transformer.setOutputProperty(OutputKeys.INDENT, "yes");
      transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
      transformer.setOutputProperty(
          OutputKeys.OMIT_XML_DECLARATION, pom.stripLeading().startsWith("<?xml") ? "no" : "yes");
      var output = new StringWriter();
      transformer.transform(new DOMSource(document), new StreamResult(output));
      return output.toString();
    } catch (ParserConfigurationException
        | SAXException
        | TransformerException
        | IllegalArgumentException failure) {
      throw new IOException("Cannot align the service's capability adapters", failure);
    }
  }

  private static @Nullable Element child(Element parent, String name) {
    for (Element element : children(parent))
      if (element.getLocalName().equals(name)) return element;
    return null;
  }

  private static String text(Element parent, String name) {
    Element value = child(parent, name);
    return value == null ? "" : value.getTextContent().strip();
  }

  private static Element element(Document document, String name, @Nullable String value) {
    Element result =
        document.createElementNS(document.getDocumentElement().getNamespaceURI(), name);
    if (value != null) result.setTextContent(value);
    return result;
  }

  private static List<Element> children(Element parent) {
    List<Element> elements = new ArrayList<>();
    for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
      if (node instanceof Element element) elements.add(element);
    return elements;
  }

  private static void removeWhitespace(Element parent) {
    for (Node node = parent.getFirstChild(); node != null; ) {
      Node next = node.getNextSibling();
      if (node instanceof Element element) removeWhitespace(element);
      else if (node.getNodeType() == Node.TEXT_NODE && node.getTextContent().isBlank())
        parent.removeChild(node);
      node = next;
    }
  }
}
