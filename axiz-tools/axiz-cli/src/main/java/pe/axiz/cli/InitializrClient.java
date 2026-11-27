package pe.axiz.cli;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import pe.axiz.codegen.openapi.Json;

/**
 * Modo en línea: solicita al initializr de axiz que genere el proyecto. La autenticación usa el
 * token propio del usuario de {@code AXIZ_TOKEN} (obtenido mediante el SSO corporativo); la CLI
 * nunca lo almacena.
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

  /** Genera un proyecto y devuelve el ZIP. */
  byte[] generate(Map<String, Object> request) throws IOException {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(server.resolve("/v1/projects"))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .header("Accept", "application/zip, application/problem+json")
            .POST(HttpRequest.BodyPublishers.ofString(Json.write(request)));
    if (token != null && !token.isBlank()) {
      b.header("Authorization", "Bearer " + token);
    }
    try {
      HttpResponse<byte[]> response =
          http.send(
              b.build(),
              HttpResponse.BodyHandlers.limiting(
                  HttpResponse.BodyHandlers.ofByteArray(), ZipExtractor.MAX_TOTAL));
      if (response.statusCode() != 200) {
        // Un diagnóstico remoto puede reproducir credenciales, valores del contrato o códigos de
        // control de terminal.
        throw new IOException("initializr answered " + response.statusCode());
      }
      return response.body();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while calling the initializr", e);
    }
  }
}
