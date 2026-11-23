package pe.axiz.codegen.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import pe.axiz.codegen.openapi.SafeYaml;
import pe.axiz.codegen.output.SafePaths;

/**
 * Ubica la aplicación en un reactor de Maven declarado; los metadatos del proyecto permanecen en su
 * raíz.
 */
public final class ProjectLayout {
  private final Path root;
  private final Path module;
  private final Path pom;
  private final String name;

  private ProjectLayout(Path root, Path module, Path pom, String name) {
    this.root = root;
    this.module = module;
    this.pom = pom;
    this.name = name;
  }

  /** Resuelve un módulo de servicio y rechaza aplicaciones ambiguas y rutas no declaradas. */
  public static ProjectLayout read(Path directory, ProjectManifest manifest) throws IOException {
    Path root = directory.toAbsolutePath().normalize();
    Path rootPom = SafePaths.target(root, "pom.xml");
    if (!manifest.isService() || !Files.exists(rootPom))
      return new ProjectLayout(root, root, rootPom, manifest.name());
    List<MavenReactor.Module> reactor = MavenReactor.read(root);
    List<Path> modules = reactor.stream().map(m -> SafePaths.parentOf(m.pom())).distinct().toList();
    List<Path> candidates = new ArrayList<>();
    for (Path module : modules) {
      for (String extension : List.of("yaml", "yml", "properties")) {
        Path config =
            SafePaths.target(
                root,
                root.relativize(module.resolve("src/main/resources/application." + extension))
                    .toString());
        if (Files.isRegularFile(config)) {
          candidates.add(module);
          break;
        }
      }
    }
    List<Path> owners = new ArrayList<>();
    for (String contract : new String[] {manifest.openapi(), manifest.asyncapi()}) {
      if (contract == null) continue;
      Path path = SafePaths.target(root, contract);
      modules.stream()
          .filter(path::startsWith)
          .max(Comparator.comparingInt(Path::getNameCount))
          .ifPresent(owners::add);
    }
    List<Path> selected = candidates.stream().filter(owners::contains).distinct().toList();
    Path application;
    if (candidates.size() == 1) application = candidates.getFirst();
    else if (selected.size() == 1) application = selected.getFirst();
    else if (candidates.isEmpty() && modules.size() == 1) application = root;
    else
      throw new IOException(
          "Cannot select one Maven service module; point the manifest contracts at its application"
              + " module");
    List<Path> poms =
        reactor.stream()
            .map(MavenReactor.Module::pom)
            .filter(p -> SafePaths.parentOf(p).equals(application))
            .toList();
    if (poms.size() != 1) throw new IOException("Ambiguous Maven POMs for the service module");
    return new ProjectLayout(root, application, poms.getFirst(), manifest.name());
  }

  /** Raíz que contiene el manifiesto, los snapshots de generación y el registro de identidades. */
  public Path root() {
    return root;
  }

  /** Directorio de la aplicación, posiblemente bajo un agregador. */
  public Path module() {
    return module;
  }

  /**
   * El POM declarado del servicio, incluidos los reactores que usan un archivo POM con nombre
   * explícito.
   */
  public Path pom() {
    return pom;
  }

  /** Resuelve un destino relativo al servicio sin seguir enlaces simbólicos. */
  public Path target(String relative) throws IOException {
    Path target = SafePaths.target(module, relative);
    return SafePaths.target(root, root.relativize(target).toString());
  }

  /**
   * Convierte un nombre de archivo generado relativo al servicio en un nombre de archivo relativo
   * al proyecto.
   */
  public String relative(String relative) throws IOException {
    return root.relativize(target(relative)).toString().replace('\\', '/');
  }

  /** Charts propios existentes, preservando sus directorios tras renombrar un despliegue. */
  public List<Path> charts() throws IOException {
    List<Path> result = new ArrayList<>();
    for (Path base : Stream.of(root, module).distinct().toList()) {
      Path helm = SafePaths.target(root, root.relativize(base.resolve("deploy/helm")).toString());
      if (!Files.isDirectory(helm)) continue;
      try (Stream<Path> charts = Files.list(helm)) {
        for (Path chart : charts.sorted().toList()) {
          SafePaths.target(root, root.relativize(chart).toString());
          if (!Files.isDirectory(chart)) continue;
          Path metadata =
              SafePaths.target(root, root.relativize(chart.resolve("Chart.yaml")).toString());
          Path valuesFile =
              SafePaths.target(root, root.relativize(chart.resolve("values.yaml")).toString());
          if (Files.isRegularFile(valuesFile) && !Files.isRegularFile(metadata))
            throw new IOException("Helm chart is missing Chart.yaml: " + root.relativize(chart));
          Object doc =
              Files.isRegularFile(metadata)
                  ? SafeYaml.create(1_000_000).load(Files.readString(metadata))
                  : null;
          if (SafePaths.fileName(chart).equals(name)
              || (doc instanceof Map<?, ?> values && name.equals(values.get("name"))))
            result.add(chart);
        }
      }
    }
    return List.copyOf(result);
  }
}
