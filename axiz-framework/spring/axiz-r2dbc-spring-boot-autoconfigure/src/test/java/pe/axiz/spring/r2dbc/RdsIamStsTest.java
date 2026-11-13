package pe.axiz.spring.r2dbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import pe.axiz.spring.foundation.aws.AwsIdentity;
import pe.axiz.spring.foundation.aws.AxizAwsIdentityAutoConfiguration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

/** Uses the actual framework token factory and signer, with no AWS or database connection. */
class RdsIamStsTest {
  @Test
  void applicationIdentitySignsRdsTokensWithoutUsingTheWorkloadIdentity() throws Exception {
    var beans = new DefaultListableBeanFactory();
    beans.registerSingleton(
        "identity", (AwsCredentialsProvider) () -> AwsBasicCredentials.create("D3_RDS", "fixture"));
    try (var context = context(beans)) {
      var passwords =
          AwsIamTokens.provider(
              "r2dbc:postgresql://db.example:5432/app",
              "iam_user",
              new AxizR2dbcProperties.Iam(true, "us-east-1", Duration.ofMinutes(10)),
              "us-east-1",
              Clock.systemUTC(),
              context.getBean(AwsIdentity.class));
      try {
        assertTrue(passwords.password().toString().contains("D3_RDS"));
      } finally {
        ((AutoCloseable) passwords).close();
      }
    }
  }

  private static AnnotationConfigApplicationContext context(DefaultListableBeanFactory beans) {
    var context = new AnnotationConfigApplicationContext(beans);
    context.register(AxizAwsIdentityAutoConfiguration.class);
    context.refresh();
    return context;
  }

  @TempDir Path temporary;

  @Test
  void databaseTokensUseTemporaryWebIdentityCredentialsAndRejectInvalidIrsa() throws Exception {
    for (boolean accepted : List.of(true, false)) {
      var sts = new AtomicInteger();
      var imds = new AtomicInteger();
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/imds",
          exchange -> {
            imds.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
          });
      server.createContext(
          "/",
          exchange -> {
            sts.incrementAndGet();
            String request =
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (!request.contains("AssumeRoleWithWebIdentity")
                || !request.contains("fixture-token"))
              throw new AssertionError("STS must receive the actual web identity request");
            String response =
                accepted
                    ? """
                    <AssumeRoleWithWebIdentityResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/">
                    <AssumeRoleWithWebIdentityResult><Credentials><AccessKeyId>ASIAFIXTURE</AccessKeyId>
                    <SecretAccessKey>fixture-secret</SecretAccessKey><SessionToken>fixture-session</SessionToken>
                    <Expiration>2099-01-01T00:00:00Z</Expiration></Credentials></AssumeRoleWithWebIdentityResult>
                    </AssumeRoleWithWebIdentityResponse>
                    """
                    : "<ErrorResponse><Error><Code>InvalidIdentityToken</Code><Message>fixture"
                        + " denied</Message></Error></ErrorResponse>";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(accepted ? 200 : 400, bytes.length);
            try (var output = exchange.getResponseBody()) {
              output.write(bytes);
            }
          });
      server.start();
      try {
        Path token = temporary.resolve("token");
        Files.writeString(token, "fixture-token");
        Path log = temporary.resolve("rds-" + accepted + ".log");
        var builder =
            new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp",
                    System.getProperty(
                        "surefire.test.class.path", System.getProperty("java.class.path")),
                    Probe.class.getName(),
                    Boolean.toString(accepted))
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        var env = builder.environment();
        env.keySet().removeIf(key -> key.startsWith("AWS_"));
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        env.put("AXIZ_CONFIG_ENVIRONMENT", "test");
        env.put("AXIZ_TRANSPORT_ALLOW_PLAIN_HTTP", "true");
        env.put("AWS_REGION", "us-east-1");
        env.put("AWS_ROLE_ARN", "arn:aws:iam::123456789012:role/fixture");
        env.put("AWS_WEB_IDENTITY_TOKEN_FILE", token.toString());
        env.put("AWS_ENDPOINT_URL_STS", endpoint);
        env.put("AWS_EC2_METADATA_SERVICE_ENDPOINT", endpoint + "/imds");
        env.put("AWS_CONFIG_FILE", "/dev/null");
        env.put("AWS_SHARED_CREDENTIALS_FILE", "/dev/null");
        Process child = builder.start();
        try {
          assertTrue(child.waitFor(30, TimeUnit.SECONDS));
          assertEquals(0, child.exitValue(), Files.readString(log));
        } finally {
          if (child.isAlive()) child.destroyForcibly();
        }
        assertTrue(sts.get() > 0);
        assertEquals(0, imds.get());
      } finally {
        server.stop(0);
      }
    }
  }

  public static final class Probe {
    private Probe() {}

    public static void main(String[] args) throws Exception {
      boolean accepted = Boolean.parseBoolean(args[0]);
      try (var context = context(new DefaultListableBeanFactory())) {
        var provider =
            AwsIamTokens.provider(
                "r2dbc:postgresql://db.example:5432/app",
                "iam_user",
                new AxizR2dbcProperties.Iam(true, "us-east-1", Duration.ofMinutes(10)),
                "us-east-1",
                Clock.systemUTC(),
                context.getBean(AwsIdentity.class));
        try {
          String password = provider.password().toString();
          if (!accepted) throw new AssertionError("Invalid IRSA accepted");
          if (!password.contains("X-Amz-Security-Token=fixture-session")
              || !password.contains("ASIAFIXTURE"))
            throw new AssertionError("RDS token did not use temporary STS credentials");
        } finally {
          ((AutoCloseable) provider).close();
        }
      } catch (RuntimeException failure) {
        if (accepted) throw failure;
      }
    }
  }
}
