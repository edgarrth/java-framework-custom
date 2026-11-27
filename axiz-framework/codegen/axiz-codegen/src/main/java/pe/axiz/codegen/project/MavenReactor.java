package pe.axiz.codegen.project;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.jspecify.annotations.Nullable;
import pe.axiz.codegen.output.SafePaths;

/**
 * Lee los módulos declarados del reactor sin seguir enlaces, entidades externas ni rutas externas.
 */
public final class MavenReactor {
  private MavenReactor() {}

  /** Un módulo declarado y su padre de Axiz, sin resolver ni ejecutar Maven. */
  public record Module(Path pom, @Nullable String parentVersion, boolean axizParent) {}

  /** Lee el reactor declarado y rechaza rutas de módulo inseguras, repetidas o inexistentes. */
  public static List<Module> read(Path directory) throws IOException {
    Path root = directory.toAbsolutePath().normalize();
    Map<Path, Module> modules = new LinkedHashMap<>();
    Deque<Path> pending = new ArrayDeque<>();
    pending.add(root.resolve("pom.xml"));
    while (!pending.isEmpty()) {
      if (modules.size() >= 10_000) throw new IOException("Maven reactor exceeds 10000 modules");
      Path candidate = pending.removeFirst().normalize();
      if (!candidate.startsWith(root)) throw new IOException("Maven module is outside the project");
      Path pom = SafePaths.target(root, root.relativize(candidate).toString());
      if (modules.containsKey(pom))
        throw new IOException("Duplicate or cyclic Maven module: " + root.relativize(pom));
      Descriptor descriptor = parse(pom);
      modules.put(pom, new Module(pom, descriptor.parentVersion(), descriptor.axizParent()));
      for (String child : descriptor.children()) {
        if (child.isBlank() || child.contains("${") || Path.of(child).isAbsolute()) {
          throw new IOException("Maven module must have a literal relative path");
        }
        Path childDirectory = SafePaths.parentOf(pom).resolve(child);
        pending.add(
            Files.isRegularFile(childDirectory)
                ? childDirectory
                : childDirectory.resolve("pom.xml"));
      }
    }
    return List.copyOf(modules.values());
  }

  private record Descriptor(
      @Nullable String parentVersion, boolean axizParent, List<String> children) {}

  private static Descriptor parse(Path pom) throws IOException {
    if (Files.size(pom) > 2 * 1024 * 1024) throw new IOException("Maven POM exceeds 2 MiB");
    XMLInputFactory factory = XMLInputFactory.newFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    factory.setXMLResolver(
        (publicId, systemId, base, namespace) -> {
          throw new XMLStreamException("External XML resources are forbidden");
        });
    Map<String, String> parent = new LinkedHashMap<>();
    List<String> children = new ArrayList<>();
    Deque<String> path = new ArrayDeque<>();
    StringBuilder content = new StringBuilder();
    boolean project = false;
    try (InputStream input = Files.newInputStream(pom)) {
      XMLStreamReader reader = factory.createXMLStreamReader(input);
      try {
        while (reader.hasNext()) {
          int event = reader.next();
          if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) {
            throw new XMLStreamException("DTD and XML entities are forbidden");
          }
          if (event == XMLStreamConstants.START_ELEMENT) {
            if (path.isEmpty()) {
              if (!reader.getLocalName().equals("project"))
                throw new XMLStreamException("Expected Maven project");
              project = true;
            }
            if (path.size() >= 128)
              throw new XMLStreamException("Maven XML nesting limit exceeded");
            path.addLast(reader.getLocalName());
            content.setLength(0);
          } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
            content.append(reader.getText());
          } else if (event == XMLStreamConstants.END_ELEMENT) {
            String location = String.join("/", path);
            if (location.equals("project/modules/module")) children.add(content.toString().strip());
            if (location.equals("project/parent/groupId")
                || location.equals("project/parent/artifactId")
                || location.equals("project/parent/version")) {
              if (parent.putIfAbsent(reader.getLocalName(), content.toString().strip()) != null) {
                throw new XMLStreamException("Duplicate Maven parent field");
              }
            }
            path.removeLast();
          }
        }
      } finally {
        reader.close();
      }
    } catch (XMLStreamException error) {
      throw new IOException("Invalid Maven XML in " + SafePaths.fileName(pom), error);
    }
    if (!project) throw new IOException("Missing Maven project");
    boolean axiz =
        "pe.axiz".equals(parent.get("groupId"))
            && parent.getOrDefault("artifactId", "").startsWith("axiz-parent");
    return new Descriptor(parent.get("version"), axiz, List.copyOf(children));
  }
}
