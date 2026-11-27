package pe.axiz.adapter.http.jdk;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import pe.axiz.core.soap.SoapTransport;
import pe.axiz.core.soap.SoapVersion;

/** Transporte SOAP acotado. Quien llama es dueño del cliente HTTP inyectado y lo cierra. */
public final class JdkSoapTransport implements SoapTransport {
  private static final long DEFAULT_MAX_RESPONSE_BYTES = 1_048_576;
  private final HttpClient http;
  private final long maxResponseBytes;

  public JdkSoapTransport(HttpClient http) {
    this(http, DEFAULT_MAX_RESPONSE_BYTES);
  }

  public JdkSoapTransport(HttpClient http, long maxResponseBytes) {
    this.http = Objects.requireNonNull(http);
    if (http.followRedirects() != HttpClient.Redirect.NEVER)
      throw new IllegalArgumentException("SOAP transport must disable automatic redirects");
    if (maxResponseBytes < 1 || maxResponseBytes > 16_777_216)
      throw new IllegalArgumentException(
          "SOAP response limit must be between 1 and 16777216 bytes");
    this.maxResponseBytes = maxResponseBytes;
  }

  @Override
  public Reply send(
      URI endpoint, SoapVersion version, String action, String envelope, Duration timeout)
      throws IOException, InterruptedException {
    if (!timeout.isPositive()) throw new IllegalArgumentException("SOAP timeout must be positive");
    if (action.length() > 2048 || action.chars().anyMatch(c -> c < 32 || c == 127 || c == '"'))
      throw new IllegalArgumentException("Invalid SOAP action");
    var request =
        HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .POST(HttpRequest.BodyPublishers.ofString(envelope, StandardCharsets.UTF_8));
    if (version == SoapVersion.SOAP_11) {
      request
          .header("Content-Type", version.contentType())
          .header("SOAPAction", "\"" + action + "\"");
    } else {
      request.header("Content-Type", version.contentType() + "; action=\"" + action + "\"");
    }
    var response =
        http.send(
            request.build(),
            HttpResponse.BodyHandlers.limiting(
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), maxResponseBytes));
    return new Reply(response.statusCode(), response.body());
  }
}
