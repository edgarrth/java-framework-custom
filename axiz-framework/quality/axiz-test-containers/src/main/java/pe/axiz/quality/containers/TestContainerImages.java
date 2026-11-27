package pe.axiz.quality.containers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.TestcontainersConfiguration;

/**
 * Construye imágenes de prueba parcheadas a partir de las mismas recetas que los entornos locales
 * generados. La construcción es diferida: definir un contenedor no contacta a Docker, por lo que
 * las pruebas pueden verificar primero la disponibilidad. El hash completo del Dockerfile
 * identifica la imagen local; no se necesita un registro privado de imágenes.
 */
public final class TestContainerImages {
  private TestContainerImages() {}

  public static PostgreSQLContainer postgres() {
    String recipe = recipe("postgres");
    var container =
        new PostgreSQLContainer(
            DockerImageName.parse(base(recipe)).asCompatibleSubstituteFor("postgres"));
    container.setImage(image("postgres"));
    return container;
  }

  public static GenericContainer<?> valkey() {
    return new GenericContainer<>(image("valkey")).withExposedPorts(6379);
  }

  /**
   * Emulador de AWS construido a partir de la misma distribución fijada que se usa en las demos
   * locales.
   */
  public static LocalStackContainer localstack() {
    var container =
        new LocalStackContainer(
            DockerImageName.parse(base(recipe("localstack")))
                .asCompatibleSubstituteFor("localstack/localstack"));
    container.setImage(image("localstack"));
    return container;
  }

  public static GenericContainer<?> wiremock() {
    return new GenericContainer<>(image("wiremock"))
        .withExposedPorts(8080)
        .waitingFor(Wait.forHttp("/__admin/mappings"));
  }

  public static GenericContainer<?> keycloak() {
    return new GenericContainer<>(image("keycloak"))
        .withExposedPorts(8080)
        .withCommand("start-dev")
        .waitingFor(Wait.forHttp("/realms/master/.well-known/openid-configuration"))
        .withStartupTimeout(Duration.ofMinutes(2));
  }

  public static KafkaContainer kafka() {
    var container =
        new KafkaContainer(
            DockerImageName.parse(base(recipe("kafka"))).asCompatibleSubstituteFor("apache/kafka"));
    container.setImage(kafkaImage());
    return container;
  }

  /**
   * Imagen diferida para subclases personalizadas de KafkaContainer, incluidos los fixtures de
   * listener TLS.
   */
  public static Future<String> kafkaImage() {
    return image("kafka");
  }

  static ImageFromDockerfile image(String name) {
    requirePatchedReaper(TestcontainersConfiguration.getInstance().getImageSubstitutorClassName());
    var files = files(name);
    var image = new ImageFromDockerfile(imageName(name, files), false);
    files.forEach(image::withFileFromString);
    return image;
  }

  static void requirePatchedReaper(String configured) {
    if (!PatchedRyukImageSubstitutor.class.getName().equals(configured))
      throw new IllegalStateException(
          "Axiz containers require image.substitutor="
              + PatchedRyukImageSubstitutor.class.getName());
  }

  static Map<String, String> files(String name) {
    var files = new LinkedHashMap<String, String>();
    for (String file : manifest(name)) {
      if (!file.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
        throw new IllegalStateException("Invalid container recipe resource");
      }
      files.put(file, resource(name + "/" + file));
    }
    return files;
  }

  private static List<String> manifest(String name) {
    try (var input =
        TestContainerImages.class.getResourceAsStream("images/" + name + "/files.list")) {
      if (input == null) return List.of("Dockerfile");
      return new String(input.readAllBytes(), StandardCharsets.UTF_8)
          .lines()
          .filter(line -> !line.isBlank())
          .toList();
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read container recipe manifest", failure);
    }
  }

  static String imageName(String name, Map<String, String> files) {
    if (files.size() == 1 && files.containsKey("Dockerfile")) {
      return "axiz-test/" + name + ":" + fingerprint(files.get("Dockerfile"));
    }
    var content = new StringBuilder();
    files.forEach((path, value) -> content.append(path).append('\0').append(value).append('\0'));
    return "axiz-test/" + name + ":" + fingerprint(content.toString());
  }

  static String base(String recipe) {
    return recipe
        .lines()
        .filter(line -> line.startsWith("FROM "))
        .flatMap(line -> Pattern.compile("\\s+").splitAsStream(line.substring(5).strip()).limit(1))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Container recipe requires FROM"));
  }

  static String fingerprint(String recipe) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(recipe.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("The JVM must support SHA-256", failure);
    }
  }

  static String recipe(String name) {
    return resource(name + "/Dockerfile");
  }

  private static String resource(String path) {
    // Los nombres provienen exclusivamente de estas fábricas, nunca de rutas controladas por el
    // usuario.
    try (var input = TestContainerImages.class.getResourceAsStream("images/" + path)) {
      return new String(
          Objects.requireNonNull(input, "Missing container recipe").readAllBytes(),
          StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read container recipe", failure);
    }
  }
}
