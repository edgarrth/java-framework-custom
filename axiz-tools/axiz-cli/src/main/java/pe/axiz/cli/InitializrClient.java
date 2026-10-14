package pe.axiz.cli;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Online mode: asks the axiz initializr to generate the project. Authentication uses the user's own
 * token from {@code AXIZ_TOKEN} (obtained through the corporate SSO); the CLI never stores it.
 */
final class InitializrClient {

  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final URI server;
  private final @Nullable String token;

  InitializrClient(URI server, @Nullable String token) {
    if (!"https".equals(server.getScheme())
        && !"localhost".equals(server.getHost())
        && !"127.0.0.1".equals(server.getHost())) {
      throw new IllegalArgumentException("The initializr must be reached over HTTPS: " + server);
    }
    this.server = server;
    this.token = token;
  }

  /** Generates a project and returns the ZIP. */
  byte[] generate(Map<String, Object> request) throws IOException {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(server.resolve("/v1/projects"))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .header("Accept", "application/zip, application/problem+json")
            .POST(HttpRequest.BodyPublishers.ofString(JsonWriter.write(request)));
    if (token != null && !token.isBlank()) {
      b.header("Authorization", "Bearer " + token);
    }
    try {
      HttpResponse<byte[]> response = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() != 200) {
        String body = new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
        throw new IOException(
            "initializr answered "
                + response.statusCode()
                + ": "
                + (body.length() > 2000 ? body.substring(0, 2000) : body));
      }
      return response.body();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while calling the initializr", e);
    }
  }
}
