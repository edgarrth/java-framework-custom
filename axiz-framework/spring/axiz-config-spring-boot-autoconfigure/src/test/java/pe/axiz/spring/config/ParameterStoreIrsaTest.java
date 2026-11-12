package pe.axiz.spring.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import pe.axiz.test.support.AwsIdentityFixture;

class ParameterStoreIrsaTest {

  @TempDir Path temporary;

  @Test
  void resolvesTemporaryIdentityAndNeverFallsBackToNodeCredentials() throws Exception {
    for (boolean accepted : new boolean[] {true, false}) {
      try (var fixture = new AwsIdentityFixture(accepted)) {
        Path log = temporary.resolve("identity-" + accepted + ".log");
        var process =
            new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp",
                    System.getProperty(
                        "surefire.test.class.path", System.getProperty("java.class.path")),
                    Probe.class.getName(),
                    Boolean.toString(accepted),
                    fixture.endpoint())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        process.environment().clear();
        process.environment().putAll(fixture.environment());
        Process child = process.start();
        try {
          assertTrue(child.waitFor(45, TimeUnit.SECONDS));
          assertEquals(0, child.exitValue(), Files.readString(log));
        } finally {
          if (child.isAlive()) child.destroyForcibly();
        }
        assertTrue(fixture.stsRequests() > 0);
        assertEquals(0, fixture.imdsRequests());
        assertEquals(0, fixture.invalidRequests());
        if (accepted) assertTrue(fixture.ssmRequests() > 0);
        else assertEquals(0, fixture.ssmRequests());
      }
    }
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class Application {}

  public static final class Probe {
    private Probe() {}

    public static void main(String[] args) {
      boolean accepted = Boolean.parseBoolean(args[0]);
      var app = new SpringApplication(Application.class);
      app.setWebApplicationType(WebApplicationType.NONE);
      try (var context =
          app.run(
              "--spring.config.import=aws-parameterstore:/fixture/",
              "--spring.cloud.aws.region.static=us-east-1",
              "--spring.cloud.aws.parameterstore.endpoint=" + args[1],
              "--spring.cloud.aws.parameterstore.enabled=true",
              "--spring.cloud.aws.secretsmanager.enabled=false")) {
        if (!accepted)
          throw new AssertionError("Invalid web identity accepted during config bootstrap");
        if (!"ok".equals(context.getEnvironment().getProperty("loaded")))
          throw new AssertionError("SSM property not loaded");
      } catch (RuntimeException failure) {
        if (accepted) throw failure;
      }
    }
  }
}
