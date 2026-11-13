package pe.axiz.adapter.aws.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import pe.axiz.adapter.aws.identity.AwsCredentialSources;
import pe.axiz.core.commons.net.TransportSecurityPolicy;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Isolated("Changes AWS system properties to exercise credential policy and lifecycle")
class IrsaCredentialsTest {
  @TempDir Path temporary;

  @Test
  void resolvesTemporaryCredentialsAndNeverFallsThroughToImdsOnFailure() throws Exception {
    for (String route : List.of("source", "sync", "async", "explicit")) {
      verify(route, true);
      if (!route.equals("explicit")) verify(route, false);
    }
  }

  @Test
  void retainsDefaultSdkStaticCredentialsWhenIrsaIsNotDeclared() throws Exception {
    verify("default", true);
  }

  @Test
  void staticCredentialsCannotOverrideIrsaAndIncompleteDeclarationsFail() {
    var keys =
        List.of(
            "aws.roleArn",
            "aws.region",
            "aws.webIdentityTokenFile",
            "aws.accessKeyId",
            "aws.secretAccessKey",
            "aws.sessionToken");
    Map<String, String> previous = new LinkedHashMap<>();
    keys.forEach(key -> previous.put(key, System.getProperty(key)));
    try {
      System.setProperty("aws.accessKeyId", "local-policy");
      System.setProperty("aws.region", "us-east-1");
      System.setProperty("aws.secretAccessKey", "local-policy");
      System.clearProperty("aws.sessionToken");
      System.setProperty("aws.roleArn", "arn:aws:iam::123456789012:role/fixture");
      System.setProperty("aws.webIdentityTokenFile", "/nonexistent/fixture-token");
      var source = AwsCredentialSources.create(TransportSecurityPolicy.system());
      try {
        assertThrows(UncheckedIOException.class, () -> source.provider().resolveCredentials());
      } finally {
        source.close();
        source.close();
      }
      try (var client = AwsClients.build(DynamoDbClient.builder(), "us-east-1", null)) {
        assertThrows(
            UncheckedIOException.class,
            () ->
                client.serviceClientConfiguration().credentialsProvider().resolveIdentity().join());
      }
      System.clearProperty("aws.webIdentityTokenFile");
      assertThrows(
          IllegalStateException.class,
          () -> AwsCredentialSources.create(TransportSecurityPolicy.system()));
      System.setProperty("aws.webIdentityTokenFile", " ");
      assertThrows(
          IllegalStateException.class,
          () -> AwsCredentialSources.create(TransportSecurityPolicy.system()));
      System.setProperty("aws.webIdentityTokenFile", "/nonexistent/fixture-token");
      System.setProperty("aws.roleArn", " ");
      assertThrows(
          IllegalStateException.class,
          () -> AwsCredentialSources.create(TransportSecurityPolicy.system()));
    } finally {
      previous.forEach(
          (key, value) -> {
            if (value == null) System.clearProperty(key);
            else System.setProperty(key, value);
          });
    }
  }

  private void verify(String route, boolean success) throws Exception {
    var stsCalls = new AtomicInteger();
    var imdsCalls = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          stsCalls.incrementAndGet();
          String request =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          if (!request.contains("AssumeRoleWithWebIdentity") || !request.contains("fixture-token"))
            throw new AssertionError("STS must receive the web identity token");
          String response =
              success
                  ? """
                  <AssumeRoleWithWebIdentityResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/">
                  <AssumeRoleWithWebIdentityResult><Credentials><AccessKeyId>ASIAFIXTURE</AccessKeyId>
                  <SecretAccessKey>fixture-secret</SecretAccessKey><SessionToken>fixture-session</SessionToken>
                  <Expiration>2099-01-01T00:00:00Z</Expiration></Credentials>
                  <SubjectFromWebIdentityToken>fixture</SubjectFromWebIdentityToken>
                  <AssumedRoleUser><Arn>arn:aws:sts::123456789012:assumed-role/fixture/session</Arn>
                  <AssumedRoleId>fixture:session</AssumedRoleId></AssumedRoleUser>
                  </AssumeRoleWithWebIdentityResult></AssumeRoleWithWebIdentityResponse>
                  """
                  : "<ErrorResponse><Error><Code>InvalidIdentityToken</Code><Message>fixture"
                      + " denied</Message></Error></ErrorResponse>";
          byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "text/xml");
          exchange.sendResponseHeaders(success ? 200 : 400, bytes.length);
          try (var output = exchange.getResponseBody()) {
            output.write(bytes);
          }
        });
    server.createContext(
        "/imds",
        exchange -> {
          imdsCalls.incrementAndGet();
          exchange.sendResponseHeaders(500, -1);
          exchange.close();
        });
    server.start();
    try {
      Path token = temporary.resolve("token");
      Files.writeString(token, "fixture-token");
      Path log = temporary.resolve(route + "-" + success + ".log");
      var builder =
          new ProcessBuilder(
                  Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                  "-cp",
                  System.getProperty(
                      "surefire.test.class.path", System.getProperty("java.class.path")),
                  Probe.class.getName(),
                  route,
                  Boolean.toString(success))
              .redirectErrorStream(true)
              .redirectOutput(log.toFile());
      var env = builder.environment();
      env.keySet().removeIf(key -> key.startsWith("AWS_") || key.startsWith("AXIZ_"));
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
      env.put("AWS_ACCESS_KEY_ID", "forgotten-env-identity");
      env.put("AWS_SECRET_ACCESS_KEY", "fixture-secret");
      if (route.equals("default")) {
        env.remove("AWS_ROLE_ARN");
        env.remove("AWS_WEB_IDENTITY_TOKEN_FILE");
      }
      Process child = builder.start();
      try {
        assertTrue(child.waitFor(30, TimeUnit.SECONDS), route + " timed out");
        assertEquals(0, child.exitValue(), Files.readString(log));
      } finally {
        if (child.isAlive()) child.destroyForcibly();
      }
      assertEquals(0, imdsCalls.get(), "IRSA must not consult node credentials");
      if (!route.equals("explicit") && !route.equals("default"))
        assertTrue(stsCalls.get() > 0, "STS must actually be exercised");
      else assertEquals(0, stsCalls.get(), "Explicit providers must be preserved");
    } finally {
      server.stop(0);
    }
  }

  public static final class Probe {
    private Probe() {}

    public static void main(String[] args) {
      boolean success = Boolean.parseBoolean(args[1]);
      System.setProperty("aws.accessKeyId", "forgotten-system-identity");
      System.setProperty("aws.secretAccessKey", "fixture-secret");
      try {
        switch (args[0]) {
          case "default" -> {
            var source = AwsCredentialSources.create(TransportSecurityPolicy.system());
            try {
              if (!source
                  .provider()
                  .resolveCredentials()
                  .accessKeyId()
                  .equals("forgotten-system-identity"))
                throw new AssertionError("Default SDK chain was changed without IRSA");
              System.clearProperty("aws.accessKeyId");
              System.clearProperty("aws.secretAccessKey");
              var environmentSource = AwsCredentialSources.create(TransportSecurityPolicy.system());
              try {
                if (!environmentSource
                    .provider()
                    .resolveCredentials()
                    .accessKeyId()
                    .equals("forgotten-env-identity"))
                  throw new AssertionError(
                      "Default environment credentials were changed without IRSA");
              } finally {
                environmentSource.close();
              }
            } finally {
              source.close();
            }
          }
          case "source" -> {
            var source = AwsCredentialSources.create(TransportSecurityPolicy.system());
            try {
              check(source.provider().resolveCredentials());
            } finally {
              source.close();
            }
          }
          case "async" -> {
            try (var clients = new AsyncAwsClients()) {
              check(clients.credentialsProvider().resolveCredentials());
            }
          }
          case "sync", "explicit" -> {
            var builder = DynamoDbClient.builder();
            if (args[0].equals("explicit"))
              builder.credentialsProvider(
                  StaticCredentialsProvider.create(
                      AwsBasicCredentials.create("explicit", "explicit")));
            try (var client = AwsClients.build(builder, "us-east-1", null)) {
              var credentials =
                  client
                      .serviceClientConfiguration()
                      .credentialsProvider()
                      .resolveIdentity()
                      .join();
              if (args[0].equals("explicit")) {
                if (!credentials.accessKeyId().equals("explicit"))
                  throw new AssertionError("Provider replaced");
              } else {
                if (!(credentials instanceof AwsSessionCredentials session))
                  throw new AssertionError("No session");
                check(session);
              }
            }
          }
          default -> throw new AssertionError("Unknown route");
        }
        if (!success) throw new AssertionError("An invalid IRSA token was accepted");
      } catch (RuntimeException failure) {
        if (success) throw failure;
      }
    }

    private static void check(AwsCredentials credentials) {
      if (!(credentials instanceof AwsSessionCredentials session)
          || !session.accessKeyId().equals("ASIAFIXTURE")
          || !session.sessionToken().equals("fixture-session"))
        throw new AssertionError("Expected STS temporary credentials");
    }
  }
}
