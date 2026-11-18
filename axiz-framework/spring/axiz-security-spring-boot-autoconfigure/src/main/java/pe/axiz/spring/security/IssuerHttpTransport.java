package pe.axiz.spring.security;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import pe.axiz.core.json.JsonReader;
import reactor.core.publisher.Mono;

/** Bounded native JDK transport shared by discovery and both Nimbus adapters. Never redirects. */
final class IssuerHttpTransport implements AutoCloseable {
  private static final int MAX_BYTES = 131_072;

  private record CachedKeys(String body, long until) {}

  private final Map<URI, CachedKeys> refreshedKeys = new ConcurrentHashMap<>();
  private final HttpClient client;
  private final Duration deadline;
  private final boolean allowHttp;
  private final Map<URI, IssuerRequestGate> gates = new ConcurrentHashMap<>();

  IssuerHttpTransport(AxizSecurityProperties.Jwks options, boolean allowHttp) {
    this.allowHttp = allowHttp;
    deadline = options.connectTimeout().plus(options.readTimeout());
    client =
        HttpClient.newBuilder()
            .connectTimeout(options.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  void validate(URI uri) {
    if (uri.getHost() == null
        || uri.getRawUserInfo() != null
        || uri.getRawFragment() != null
        || uri.toString().length() > 2048
        || !("https".equals(uri.getScheme()) || (allowHttp && "http".equals(uri.getScheme()))))
      throw new IllegalArgumentException("Issuer endpoints require an explicit valid HTTPS URI");
  }

  CompletableFuture<String> get(URI uri, Consumer<Map<String, Object>> validateBody) {
    return get(uri, validateBody, false);
  }

  // Both observers explicitly terminate the owned result or cancel its transport; no detached task.
  @SuppressWarnings("FutureReturnValueIgnored")
  private CompletableFuture<String> get(
      URI uri, Consumer<Map<String, Object>> validateBody, boolean keys) {
    validate(uri);
    CachedKeys cached = keys ? refreshedKeys.get(uri) : null;
    if (cached != null && cached.until() - System.nanoTime() > 0)
      return CompletableFuture.completedFuture(cached.body());
    var gate = gates.computeIfAbsent(uri, _ -> new IssuerRequestGate());
    try {
      gate.enter();
    } catch (IssuerUnavailableException unavailable) {
      return CompletableFuture.failedFuture(unavailable);
    }
    var body = new BoundedBody();
    CompletableFuture<HttpResponse<byte[]>> raw;
    try {
      raw =
          client.sendAsync(
              HttpRequest.newBuilder(uri)
                  .timeout(deadline)
                  .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                  .GET()
                  .build(),
              info -> body);
    } catch (RuntimeException failure) {
      return CompletableFuture.failedFuture(gate.failure());
    }
    var result = new CompletableFuture<String>();
    raw.copy()
        .orTimeout(deadline.toMillis(), TimeUnit.MILLISECONDS)
        .whenComplete(
            (response, failure) -> {
              if (failure != null) {
                body.cancel();
                raw.cancel(true);
                result.completeExceptionally(gate.failure());
                return;
              }
              try {
                if (response.statusCode() != 200)
                  throw new IllegalArgumentException("Issuer HTTP status");
                String text =
                    StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(response.body()))
                        .toString();
                validateBody.accept(JsonReader.parseObject(text));
                if (keys)
                  refreshedKeys.put(
                      uri, new CachedKeys(text, System.nanoTime() + TimeUnit.SECONDS.toNanos(1)));
                gate.success();
                result.complete(text);
              } catch (CharacterCodingException | RuntimeException invalid) {
                result.completeExceptionally(gate.failure());
              }
            });
    result.whenComplete(
        (value, failure) -> {
          if (result.isCancelled()) {
            body.cancel();
            raw.cancel(true);
          }
        });
    return result;
  }

  String blocking(CompletableFuture<String> request) {
    try {
      return request.get();
    } catch (InterruptedException interrupted) {
      request.cancel(true);
      Thread.currentThread().interrupt();
      throw new IssuerUnavailableException(1);
    } catch (ExecutionException failure) {
      var unavailable = IssuerUnavailableException.find(failure);
      throw unavailable != null ? unavailable : new IssuerUnavailableException(1);
    }
  }

  RestOperations rest(URI endpoint) {
    var rest = new RestTemplate(new JdkClientHttpRequestFactory(client));
    rest.setInterceptors(
        List.of(
            (request, body, execution) -> {
              if (!endpoint.equals(request.getURI())) throw new IssuerUnavailableException(60);
              byte[] bytes =
                  blocking(get(endpoint, IssuerHttpTransport::keys, true))
                      .getBytes(StandardCharsets.UTF_8);
              return new SnapshotResponse(bytes);
            }));
    return rest;
  }

  WebClient web(URI endpoint) {
    return WebClient.builder()
        .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_BYTES))
        .exchangeFunction(
            request ->
                Mono.defer(
                    () -> {
                      if (!endpoint.equals(request.url()))
                        return Mono.error(new IssuerUnavailableException(60));
                      return Mono.fromFuture(get(endpoint, IssuerHttpTransport::keys, true))
                          .map(
                              body ->
                                  ClientResponse.create(HttpStatus.OK)
                                      .header(
                                          HttpHeaders.CONTENT_TYPE,
                                          MediaType.APPLICATION_JSON_VALUE)
                                      .body(body)
                                      .build());
                    }))
        .build();
  }

  private static void keys(Map<String, Object> body) {
    if (!(body.get("keys") instanceof List<?> keys) || keys.isEmpty() || keys.size() > 100)
      throw new IllegalArgumentException("Invalid bounded JWKS");
  }

  @Override
  public void close() {
    client.shutdownNow();
  }

  private static final class SnapshotResponse implements ClientHttpResponse {
    private final byte[] body;

    SnapshotResponse(byte[] body) {
      this.body = body;
    }

    @Override
    public HttpStatusCode getStatusCode() {
      return HttpStatus.OK;
    }

    @Override
    public String getStatusText() {
      return "OK";
    }

    @Override
    public void close() {}

    @Override
    public InputStream getBody() {
      return new ByteArrayInputStream(body);
    }

    @Override
    public HttpHeaders getHeaders() {
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      return headers;
    }
  }

  private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private volatile Flow.@Nullable Subscription subscription;

    @Override
    public CompletionStage<byte[]> getBody() {
      return result.minimalCompletionStage();
    }

    @Override
    public void onSubscribe(Flow.Subscription value) {
      subscription = value;
      if (result.isDone()) value.cancel();
      else value.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> values) {
      for (ByteBuffer value : values) {
        if (value.remaining() > MAX_BYTES - bytes.size()) {
          cancel();
          return;
        }
        byte[] chunk = new byte[value.remaining()];
        value.get(chunk);
        bytes.writeBytes(chunk);
      }
      var current = subscription;
      if (current != null && !result.isDone()) current.request(1);
    }

    @Override
    public void onError(Throwable failure) {
      result.completeExceptionally(failure);
    }

    @Override
    public void onComplete() {
      result.complete(bytes.toByteArray());
    }

    void cancel() {
      result.completeExceptionally(new IssuerUnavailableException(1));
      var current = subscription;
      if (current != null) current.cancel();
    }
  }
}
