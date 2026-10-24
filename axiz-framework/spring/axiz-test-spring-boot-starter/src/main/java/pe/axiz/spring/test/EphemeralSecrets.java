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
 * Secret files for tests, generated at run time: a fresh random 256-bit key (Base64) per name in a
 * temporary directory that is deleted when the JVM exits. No key material is ever committed, and no
 * two runs (or services) share one.
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
   * Creates the directory with one key file per name.
   *
   * @param names secret names (file names), as the service resolves them
   * @return the directory, for {@code axiz.secrets.directory}
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

  /** Creates the keys and points {@code axiz.secrets.directory} at them. */
  public static void register(DynamicPropertyRegistry registry, String... names) {
    Path dir = keys(names);
    registry.add("axiz.secrets.directory", dir::toString);
  }

  private static void delete(Path dir) {
    try (Stream<Path> files = Files.walk(dir)) {
      files.sorted(Comparator.reverseOrder()).forEach(EphemeralSecrets::deleteQuietly);
    } catch (IOException e) {
      // the temporary directory is left to the operating system
    }
  }

  private static void deleteQuietly(Path p) {
    try {
      Files.deleteIfExists(p);
    } catch (IOException e) {
      // the temporary directory is left to the operating system
    }
  }
}
