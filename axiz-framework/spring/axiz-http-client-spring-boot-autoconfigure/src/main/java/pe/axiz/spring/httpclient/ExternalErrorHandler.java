package pe.axiz.spring.httpclient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;
import pe.axiz.core.http.ExternalErrorTranslator;

/**
 * Translates error responses of a dependency into {@code ExternalServiceException} with the
 * category chosen from the status; an empty body is preserved.
 */
public class ExternalErrorHandler implements RestClient.ResponseSpec.ErrorHandler {

  private static final int MAX_BODY = 8192;

  private final String system;

  public ExternalErrorHandler(String system) {
    this.system = system;
  }

  @Override
  public void handle(HttpRequest request, ClientHttpResponse response) throws IOException {
    MediaType type = response.getHeaders().getContentType();
    String body;
    try (InputStream in = response.getBody()) {
      body = new String(in.readNBytes(MAX_BODY), StandardCharsets.UTF_8);
    }
    throw ExternalErrorTranslator.translate(
        system, response.getStatusCode().value(), type != null ? type.toString() : null, body);
  }
}
