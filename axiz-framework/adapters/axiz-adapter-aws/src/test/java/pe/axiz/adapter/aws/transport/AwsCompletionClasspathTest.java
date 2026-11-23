package pe.axiz.adapter.aws.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pe.axiz.adapter.aws.identity.AwsCredentialSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

class AwsCompletionClasspathTest {
  @TempDir Path temporary;

  @Test
  void explicitCallbackBoundsDoNotRequireMicrometerCoreAtRuntime() throws Exception {
    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    String isolated =
        Arrays.stream(classpath.split(Pattern.quote(File.pathSeparator)))
            .filter(entry -> !Path.of(entry).getFileName().toString().startsWith("micrometer-"))
            .collect(Collectors.joining(File.pathSeparator));
    Path log = temporary.resolve("without-metrics.log");
    var child =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                isolated,
                WithoutMetrics.class.getName())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    try {
      assertTrue(child.waitFor(20, TimeUnit.SECONDS));
      assertEquals(0, child.exitValue(), Files.readString(log));
    } finally {
      if (child.isAlive()) child.destroyForcibly();
    }
  }

  public static final class WithoutMetrics {
    private WithoutMetrics() {}

    public static void main(String[] args) throws Exception {
      try {
        Class.forName("io.micrometer.core.instrument.MeterRegistry");
        throw new AssertionError("Micrometer was not removed");
      } catch (ClassNotFoundException expected) {
        var source =
            StaticCredentialsProvider.create(AwsBasicCredentials.create("offline", "offline"));
        try (var clients =
            new AsyncAwsClients(
                AwsCredentialSource.borrowed(source),
                Clock.systemUTC(),
                Duration.ofDays(1),
                1,
                1,
                AsyncAwsTransportSettings.defaults())) {
          // Los frameworks pueden inspeccionar todos los métodos por reflexión mientras Micrometer
          // está ausente.
          AsyncAwsClients.class.getDeclaredMethods();
          AsyncAwsClients.class.getDeclaredConstructors();
          if (clients.credentialsProvider().resolveCredentials() == null)
            throw new AssertionError("Missing warmed credentials");
        }
      }
    }
  }
}
