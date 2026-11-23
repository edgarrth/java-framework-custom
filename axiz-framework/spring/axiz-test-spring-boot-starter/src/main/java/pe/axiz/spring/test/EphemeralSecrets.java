package pe.axiz.spring.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Comparator;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Archivos de secretos para pruebas, generados en tiempo de ejecución: una clave aleatoria nueva de
 * 256 bits (Base64) por nombre en un directorio temporal que se elimina cuando la JVM termina.
 * Nunca se versiona material de claves, y dos ejecuciones (o servicios) nunca comparten una.
 *
 * <pre>{@code
 * @DynamicPropertySource
 * static void secrets(DynamicPropertyRegistry registry) {
 *   EphemeralSecrets.register(registry, "field-key-v1", "blind-index-key");
 * }
 * }</pre>
 */
public final class EphemeralSecrets {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,128}");

  private EphemeralSecrets() {}

  /**
   * Crea el directorio con un archivo de clave por nombre.
   *
   * @param names nombres de los secretos (nombres de archivo), tal como los resuelve el servicio
   * @return el directorio, para {@code axiz.secrets.directory}
   */
  public static Path keys(String... names) {
    try {
      Path dir = Files.createTempDirectory("axiz-test-secrets-");
      Runtime.getRuntime().addShutdownHook(new Thread(() -> delete(dir)));
      for (String name : names) {
        if (!NAME.matcher(name).matches()) {
          throw new IllegalArgumentException("Invalid secret name: " + name);
        }
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        Files.writeString(
            dir.resolve(name), Base64.getEncoder().encodeToString(key), StandardCharsets.US_ASCII);
      }
      return dir;
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot create the test secrets", e);
    }
  }

  /** Crea las claves y apunta {@code axiz.secrets.directory} hacia ellas. */
  public static void register(DynamicPropertyRegistry registry, String... names) {
    Path dir = keys(names);
    registry.add("axiz.secrets.directory", dir::toString);
  }

  private static void delete(Path dir) {
    try (Stream<Path> files = Files.walk(dir)) {
      files.sorted(Comparator.reverseOrder()).forEach(EphemeralSecrets::deleteQuietly);
    } catch (IOException e) {
      // el directorio temporal se deja al sistema operativo
    }
  }

  private static void deleteQuietly(Path p) {
    try {
      Files.deleteIfExists(p);
    } catch (IOException e) {
      // el directorio temporal se deja al sistema operativo
    }
  }
}
