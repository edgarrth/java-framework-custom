package pe.axiz.quality.containers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.Future;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Builds patched test images from the same recipes as generated local environments. Construction is
 * lazy: defining a container does not contact Docker, so tests can check availability first. The
 * complete Dockerfile hash identifies the local image; no private image registry is needed.
 */
public final class TestContainerImages {
  private TestContainerImages() {}

  public static PostgreSQLContainer postgres() {
    String recipe = recipe("postgres");
    var container =
        new PostgreSQLContainer(
            DockerImageName.parse(base(recipe)).asCompatibleSubstituteFor("postgres"));
    container.setImage(image("postgres", recipe));
    return container;
  }

  public static GenericContainer<?> valkey() {
    return new GenericContainer<>(image("valkey", recipe("valkey"))).withExposedPorts(6379);
  }

  public static GenericContainer<?> wiremock() {
    return new GenericContainer<>(image("wiremock", recipe("wiremock")))
        .withExposedPorts(8080)
        .waitingFor(Wait.forHttp("/__admin/mappings"));
  }

  public static GenericContainer<?> keycloak() {
    return new GenericContainer<>(image("keycloak", recipe("keycloak")))
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

  /** Lazy image for custom KafkaContainer subclasses, including TLS listener fixtures. */
  public static Future<String> kafkaImage() {
    return image("kafka", recipe("kafka"));
  }

  private static ImageFromDockerfile image(String name, String recipe) {
    return new ImageFromDockerfile("axiz-test/" + name + ":" + fingerprint(recipe), false)
        .withFileFromString("Dockerfile", recipe);
  }

  static String base(String recipe) {
    return recipe
        .lines()
        .filter(line -> line.startsWith("FROM "))
        .map(line -> line.substring(5).strip())
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
    // The names come exclusively from these factories, never from user-controlled paths.
    try (var input =
        TestContainerImages.class.getResourceAsStream("images/" + name + "/Dockerfile")) {
      return new String(
          Objects.requireNonNull(input, "Missing container recipe").readAllBytes(),
          StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot read container recipe", failure);
    }
  }
}
