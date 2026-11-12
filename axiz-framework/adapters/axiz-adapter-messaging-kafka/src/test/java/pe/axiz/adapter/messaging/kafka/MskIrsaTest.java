package pe.axiz.adapter.messaging.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.security.auth.callback.Callback;
import javax.security.auth.login.AppConfigurationEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pe.axiz.adapter.messaging.kafka.aws.AxizIamClientCallbackHandler;
import pe.axiz.test.support.AwsIdentityFixture;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.msk.auth.iam.internals.AWSCredentialsCallback;

class MskIrsaTest {

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
      }
    }
  }

  public static final class Probe {
    private Probe() {}

    public static void main(String[] args) throws Exception {
      boolean accepted = Boolean.parseBoolean(args[0]);
      var handler = new AxizIamClientCallbackHandler();
      try {
        handler.configure(
            Map.of(),
            "AWS_MSK_IAM",
            List.of(
                new AppConfigurationEntry(
                    "software.amazon.msk.auth.iam.IAMLoginModule",
                    AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                    Map.of())));
        var callback = new AWSCredentialsCallback();
        handler.handle(new Callback[] {callback});
        if (callback.isSuccessful() != accepted)
          throw new AssertionError("Unexpected IAM callback outcome");
        if (accepted
            && (!(callback.getAwsCredentials() instanceof AwsSessionCredentials credentials)
                || !"ASIAFIXTURE".equals(credentials.accessKeyId())
                || !"fixture-session".equals(credentials.sessionToken())))
          throw new AssertionError("MSK did not receive the temporary STS session");
      } finally {
        handler.close();
        handler.close();
      }
    }
  }
}
