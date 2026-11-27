package pe.axiz.test.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixture de STS/SSM/IMDS solo en loopback con credenciales de web identity temporales y ficticias.
 */
public final class AwsIdentityFixture implements AutoCloseable {
  private final HttpServer server;
  private final Path directory;
  private final boolean accepted;
  private final AtomicInteger sts = new AtomicInteger();
  private final AtomicInteger imds = new AtomicInteger();
  private final AtomicInteger ssm = new AtomicInteger();
  private final AtomicInteger invalid = new AtomicInteger();

  public AwsIdentityFixture(boolean accepted) throws IOException {
    this.accepted = accepted;
    directory = Files.createTempDirectory("axiz-identity-");
    Files.writeString(directory.resolve("token"), "fixture-token");
    server =
        HttpServer.create(
            new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0), 0);
    server.createContext(
        "/imds",
        exchange -> {
          imds.incrementAndGet();
          respond(exchange, 500, "unavailable");
        });
    server.createContext("/", this::request);
    server.start();
  }

  private void request(HttpExchange exchange) throws IOException {
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    String target = exchange.getRequestHeaders().getFirst("X-Amz-Target");
    if (target != null && target.endsWith("GetParametersByPath")) {
      ssm.incrementAndGet();
      String authorization = exchange.getRequestHeaders().getFirst("Authorization");
      if (authorization == null
          || !authorization.contains("ASIAFIXTURE")
          || !"fixture-session"
              .equals(exchange.getRequestHeaders().getFirst("X-Amz-Security-Token")))
        invalid.incrementAndGet();
      exchange.getResponseHeaders().set("Content-Type", "application/x-amz-json-1.1");
      respond(
          exchange,
          200,
          "{\"Parameters\":[{\"Name\":\"/fixture/loaded\",\"Type\":\"String\",\"Value\":\"ok\"}]}");
      return;
    }
    sts.incrementAndGet();
    if (!body.contains("AssumeRoleWithWebIdentity") || !body.contains("fixture-token"))
      invalid.incrementAndGet();
    exchange.getResponseHeaders().set("Content-Type", "text/xml");
    respond(
        exchange,
        accepted ? 200 : 400,
        accepted
            ? """
            <AssumeRoleWithWebIdentityResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/">
            <AssumeRoleWithWebIdentityResult><Credentials><AccessKeyId>ASIAFIXTURE</AccessKeyId>
            <SecretAccessKey>fixture-secret</SecretAccessKey><SessionToken>fixture-session</SessionToken>
            <Expiration>2099-01-01T00:00:00Z</Expiration></Credentials></AssumeRoleWithWebIdentityResult>
            </AssumeRoleWithWebIdentityResponse>
            """
            : "<ErrorResponse><Error><Code>InvalidIdentityToken</Code><Message>fixture"
                + " denied</Message></Error></ErrorResponse>");
  }

  private static void respond(HttpExchange exchange, int status, String text) throws IOException {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    try (var stream = exchange.getResponseBody()) {
      stream.write(bytes);
    }
  }

  public String endpoint() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  public int stsRequests() {
    return sts.get();
  }

  public int imdsRequests() {
    return imds.get();
  }

  public int ssmRequests() {
    return ssm.get();
  }

  public int invalidRequests() {
    return invalid.get();
  }

  /**
   * El entorno del proceso hijo no tiene identidad de AWS heredada ni configuración de secretos.
   */
  public Map<String, String> environment() {
    var env = new HashMap<>(System.getenv());
    env.keySet()
        .removeIf(
            key ->
                key.startsWith("AWS_")
                    || key.startsWith("AXIZ_")
                    || key.equals("JAVA_TOOL_OPTIONS")
                    || key.equals("JDK_JAVA_OPTIONS")
                    || key.equals("_JAVA_OPTIONS"));
    env.put("AXIZ_CONFIG_ENVIRONMENT", "test");
    env.put("AXIZ_TRANSPORT_ALLOW_PLAIN_HTTP", "true");
    env.put("AWS_REGION", "us-east-1");
    env.put("AWS_ROLE_ARN", "arn:aws:iam::123456789012:role/fixture");
    env.put("AWS_WEB_IDENTITY_TOKEN_FILE", directory.resolve("token").toString());
    env.put("AWS_ENDPOINT_URL_STS", endpoint());
    env.put("AWS_EC2_METADATA_SERVICE_ENDPOINT", endpoint() + "/imds");
    env.put("AWS_CONFIG_FILE", "/dev/null");
    env.put("AWS_SHARED_CREDENTIALS_FILE", "/dev/null");
    return Map.copyOf(env);
  }

  @Override
  public void close() throws IOException {
    server.stop(0);
    Files.deleteIfExists(directory.resolve("token"));
    Files.deleteIfExists(directory);
  }
}
