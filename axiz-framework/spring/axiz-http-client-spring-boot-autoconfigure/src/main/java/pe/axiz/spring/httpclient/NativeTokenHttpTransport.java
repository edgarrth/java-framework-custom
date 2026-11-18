package pe.axiz.spring.httpclient;

import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.client.WebClient;
import pe.axiz.core.concurrent.Stages;
import pe.axiz.core.http.AsyncHttpExchange;
import pe.axiz.core.http.ClientSettings;

/** Startup-owned native DNS, HTTP and bounded pooling for the token endpoint. */
final class NativeTokenHttpTransport implements AsyncHttpExchange, AutoCloseable {
  private static final int MAX_BYTES = 65_536;
  private final NativeHttpResources resources;
  private final WebClient client;
  private final AtomicBoolean closed = new AtomicBoolean();

  NativeTokenHttpTransport(
      ClientSettings settings, ObservationRegistry observations, @Nullable SslBundle bundle) {
    resources =
        new NativeHttpResources(
            settings, Objects.requireNonNull(settings.auth().tokenUri()), 2, bundle);
    try {
      client =
          WebClient.builder()
              .clientConnector(resources.connector())
              .observationRegistry(observations)
              .observationConvention(new ReactiveClientNameObservationConvention(settings.name()))
              .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_BYTES))
              .build();
    } catch (RuntimeException | Error failure) {
      try {
        resources.close();
      } catch (RuntimeException | Error cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  @Override
  public CompletionStage<Response> exchange(Request request, long maxResponseBytes) {
    if (closed.get())
      return CompletableFuture.failedFuture(new IllegalStateException("Token transport is closed"));
    if (maxResponseBytes < 1 || maxResponseBytes > MAX_BYTES)
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("Token response limit exceeds transport capacity"));
    var outgoing =
        client
            .method(HttpMethod.valueOf(request.method()))
            .uri(request.uri())
            .headers(headers -> request.headers().forEach(headers::put));
    WebClient.RequestHeadersSpec<?> prepared = outgoing.bodyValue(request.body());
    return Stages.requireValue(
        prepared
            .exchangeToMono(
                response -> {
                  String type = response.headers().contentType().map(Object::toString).orElse(null);
                  int status = response.statusCode().value();
                  return DataBufferUtils.join(
                          response.body(BodyExtractors.toDataBuffers()),
                          Math.toIntExact(maxResponseBytes))
                      .map(
                          buffer -> {
                            try {
                              return new Response(
                                  status, type, buffer.toString(StandardCharsets.UTF_8));
                            } finally {
                              DataBufferUtils.release(buffer);
                            }
                          })
                      .defaultIfEmpty(new Response(status, type, ""));
                })
            .timeout(request.timeout())
            .contextCapture()
            .toFuture());
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) resources.close();
  }
}
