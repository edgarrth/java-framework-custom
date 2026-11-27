package pe.axiz.spring.foundation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReactiveBootstrapClasspathTest {
  @TempDir Path temporary;

  @Test
  void reactiveStartupAlsoWorksWhenTheOptionalContextPropagationJarIsPhysicallyAbsent()
      throws Exception {
    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    String isolated =
        Arrays.stream(classpath.split(Pattern.quote(File.pathSeparator)))
            .filter(
                entry ->
                    !Path.of(entry).getFileName().toString().startsWith("context-propagation-"))
            .collect(Collectors.joining(File.pathSeparator));
    Path output = temporary.resolve("bootstrap.log");
    Process child =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.security.egd=file:/dev/./urandom",
                "-cp",
                isolated,
                WithoutContext.class.getName())
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

  /** Punto de entrada de una JVM aislada, sin tipos de context-propagation en sus firmas. */
  public static final class WithoutContext {
    private WithoutContext() {}

    public static void main(String[] args) throws Exception {
      try {
        Class.forName("io.micrometer.context.ContextRegistry");
        throw new AssertionError(
            "Optional context dependency was not removed from the child classpath");
      } catch (ClassNotFoundException expected) {
        ReactiveInfrastructureBootstrap.initialize();
        if (!"native"
            .equals(
                ReactiveStages.from(() -> CompletableFuture.completedFuture("native")).block())) {
          throw new AssertionError("Native facade requires the optional context dependency");
        }
      }
    }
  }
}
