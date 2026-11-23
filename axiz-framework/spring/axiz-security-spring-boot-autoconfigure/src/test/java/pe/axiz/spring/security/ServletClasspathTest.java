package pe.axiz.spring.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ServletClasspathTest {
  @Test
  void springCanInspectTheSharedDecoderBeanWithoutReactor() throws Exception {
    String classpath =
        Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
            .filter(
                path ->
                    !path.contains("reactor-core")
                        && !path.contains("reactive-streams")
                        && !path.contains("spring-webflux"))
            .collect(Collectors.joining(File.pathSeparator));
    Process process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                classpath,
                ServletClasspathTest.class.getName())
            .redirectErrorStream(true)
            .start();
    try {
      assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertThat(process.exitValue()).as(output).isZero();
    } finally {
      process.destroyForcibly();
    }
  }

  public static void main(String[] args) throws Exception {
    try {
      Class.forName("reactor.core.publisher.Mono");
      throw new IllegalStateException("The fixture must exclude Reactor");
    } catch (ClassNotFoundException expected) {
      // La misma reflexión que realiza el post-processor de anotaciones de persistencia de Spring.
      if (TrustedIssuerDecoders.class.getDeclaredMethods().length == 0)
        throw new IllegalStateException("Decoder bean metadata is missing");
    }
  }
}
