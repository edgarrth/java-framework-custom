package pe.axiz.spring.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import pe.axiz.test.support.AwsIdentityFixture;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;

class ParameterStoreIrsaTest {

  @TempDir Path temporary;

  @Test
  void resolvesTemporaryIdentityAndNeverFallsBackToNodeCredentials() throws Exception {
    for (String mode :
        List.of(
            "valid",
            "custom-bootstrap",
            "invalid",
            "static-conflict",
            "profile-conflict",
            "incomplete")) {
      boolean accepted = "valid".equals(mode) || "custom-bootstrap".equals(mode);
      try (var fixture = new AwsIdentityFixture(!"invalid".equals(mode))) {
        Path log = temporary.resolve("identity-" + mode + ".log");
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
            .filter(
                argument ->
                    argument.startsWith("-javaagent:") && argument.contains("org.jacoco.agent"))
            .forEach(command::add);
        command.addAll(
            List.of(
                "-cp",
                System.getProperty(
                    "surefire.test.class.path", System.getProperty("java.class.path")),
                Probe.class.getName(),
                mode,
                fixture.endpoint()));
        var process =
            new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        process.environment().clear();
        process.environment().putAll(fixture.environment());
        if ("incomplete".equals(mode)) process.environment().remove("AWS_WEB_IDENTITY_TOKEN_FILE");
        Process child = process.start();
        try {
          assertTrue(child.waitFor(45, TimeUnit.SECONDS));
          assertEquals(0, child.exitValue(), Files.readString(log));
        } finally {
          if (child.isAlive()) child.destroyForcibly();
        }
        if ("valid".equals(mode) || "invalid".equals(mode)) assertTrue(fixture.stsRequests() > 0);
        else assertEquals(0, fixture.stsRequests());
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
      boolean accepted = "valid".equals(args[0]) || "custom-bootstrap".equals(args[0]);
      var options =
          new ArrayList<>(
              List.of(
                  "--spring.config.import=aws-parameterstore:/fixture/",
                  "--spring.cloud.aws.region.static=us-east-1",
                  "--spring.cloud.aws.parameterstore.endpoint=" + args[1],
                  "--spring.cloud.aws.parameterstore.enabled=true",
                  "--spring.cloud.aws.secretsmanager.enabled=false"));
      if ("static-conflict".equals(args[0]))
        options.addAll(
            List.of(
                "--spring.cloud.aws.credentials.access-key=static-fixture",
                "--spring.cloud.aws.credentials.secret-key=static-fixture"));
      if ("profile-conflict".equals(args[0]))
        options.add("--spring.cloud.aws.credentials.profile.name=other");
      var app = new SpringApplication(Application.class);
      app.setWebApplicationType(WebApplicationType.NONE);
      if ("custom-bootstrap".equals(args[0]))
        app.addBootstrapRegistryInitializer(
            registry ->
                registry.register(
                    AwsCredentialsProvider.class,
                    ignored ->
                        () ->
                            AwsSessionCredentials.create(
                                "ASIAFIXTURE", "fixture-secret", "fixture-session")));
      try (var context = app.run(options.toArray(String[]::new))) {
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
