package pe.axiz.spring.httpclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pe.axiz.core.http.ClientSettings;

class HttpClientClasspathTest {
  @TempDir Path temporary;

  @Test
  void servletCredentialsDoNotRequireTheOptionalReactiveTransportJars() throws Exception {
    var excluded =
        List.of(
            "reactor-core-",
            "reactor-netty-",
            "reactive-streams-",
            "netty-",
            "spring-webflux-",
            "spring-boot-webclient-",
            "spring-boot-reactor-netty-");
    String classpath =
        Arrays.stream(
                System.getProperty(
                        "surefire.test.class.path", System.getProperty("java.class.path"))
                    .split(Pattern.quote(File.pathSeparator)))
            .filter(
                entry ->
                    excluded.stream()
                        .noneMatch(
                            prefix -> Path.of(entry).getFileName().toString().startsWith(prefix)))
            .collect(Collectors.joining(File.pathSeparator));
    Path output = temporary.resolve("servlet.log");
    var child =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                classpath,
                PlainClasspath.class.getName())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    try {
      assertTrue(child.waitFor(20, TimeUnit.SECONDS));
      assertEquals(0, child.exitValue(), Files.readString(output));
    } finally {
      if (child.isAlive()) child.destroyForcibly();
    }
  }

  public static final class PlainClasspath {
    private PlainClasspath() {}

    public static void main(String[] args) throws Exception {
      try {
        Class.forName("reactor.core.publisher.Mono");
        throw new AssertionError("Reactive jars must be physically absent");
      } catch (ClassNotFoundException expected) {
        var settings = ClientSettings.of("plain-client", URI.create("https://example.test"));
        try (var auth =
            OutboundAuth.create(settings, null, Duration.ofSeconds(30), Clock.systemUTC())) {
          auth.apply(
              (name, value) -> {
                throw new AssertionError("No credentials configured");
              });
        }
        try (var nativeAuth =
            OutboundAuth.createAsync(settings, null, Duration.ofSeconds(30), Clock.systemUTC())) {
          if (!nativeAuth.headers().toCompletableFuture().get(3, TimeUnit.SECONDS).isEmpty()) {
            throw new AssertionError("CPU-only headers must not require a reactive transport");
          }
        }
      }
    }
  }
}
