package pe.axiz.adapter.http.jdk;

import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import pe.axiz.core.http.AsyncHttpExchange;

/** Frontera entre los datos neutrales de red y el transporte del JDK. */
final class JdkRequests {
  private JdkRequests() {}

  static HttpRequest request(AsyncHttpExchange.Request request) {
    var builder = HttpRequest.newBuilder(request.uri()).timeout(request.timeout());
    request.headers().forEach((key, values) -> values.forEach(value -> builder.header(key, value)));
    return builder
        .method(
            request.method(),
            HttpRequest.BodyPublishers.ofString(request.body(), StandardCharsets.UTF_8))
        .build();
  }
}
