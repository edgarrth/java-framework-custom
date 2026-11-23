package pe.axiz.quality.containers;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.BuildImageResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.dockerclient.DockerClientProviderStrategy;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.ImageNameSubstitutor;

/**
 * Construye el reaper inspeccionable previo a que Testcontainers lo necesite, sin iniciar otro
 * reaper.
 */
public final class PatchedRyukImageSubstitutor extends ImageNameSubstitutor {
  private final Supplier<DockerClient> clients;

  public PatchedRyukImageSubstitutor() {
    this(
        () ->
            DockerClientProviderStrategy.getClientForConfig(
                DockerClientFactory.instance().getTransportConfig()));
  }

  PatchedRyukImageSubstitutor(Supplier<DockerClient> clients) {
    this.clients = clients;
  }

  @Override
  public DockerImageName apply(DockerImageName original) {
    if (!original.getUnversionedPart().equals("testcontainers/ryuk")) return original;
    return DockerImageName.parse(ensureImage()).asCompatibleSubstituteFor(original);
  }

  private synchronized String ensureImage() {
    Map<String, String> files = TestContainerImages.files("ryuk");
    String name = TestContainerImages.imageName("ryuk", files);
    // getTransportConfig descubre Docker sin ResourceReaper.init(). Usar client() aquí generaría
    // una recursión mientras se resuelve la propia imagen del reaper.
    try (DockerClient docker = clients.get()) {
      try {
        docker.inspectImageCmd(name).exec();
        return name;
      } catch (NotFoundException missing) {
        build(docker, name, files);
        return name;
      }
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot prepare the Axiz cleanup image", failure);
    }
  }

  private static void build(DockerClient docker, String name, Map<String, String> files)
      throws IOException {
    Path directory = Files.createTempDirectory("axiz-ryuk-build-");
    try {
      for (var entry : files.entrySet()) {
        Files.writeString(
            directory.resolve(entry.getKey()), entry.getValue(), StandardCharsets.UTF_8);
      }
      try (var result = new BuildImageResultCallback()) {
        docker
            .buildImageCmd(directory.toFile())
            .withTags(Set.of(name))
            .exec(result)
            .awaitImageId(10, TimeUnit.MINUTES);
      }
    } finally {
      try (var paths = Files.walk(directory)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
      }
    }
  }

  @Override
  protected String getDescription() {
    return "Axiz inspectable Ryuk with maintained Docker SDK";
  }
}
