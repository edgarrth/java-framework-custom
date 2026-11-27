package pe.axiz.adapter.http.jdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pe.axiz.core.commons.net.TransportSecurityPolicy;
import pe.axiz.core.concurrent.Stages;
import pe.axiz.core.errors.ExternalServiceException;
import pe.axiz.core.http.AccessToken;
import pe.axiz.core.http.AsyncHttpExchange;
import pe.axiz.core.http.ClientCredentialsAsyncTokenProvider;

class AsyncClientCredentialsTest {
  private final TrackingClient http = new TrackingClient();
  // Driver HTTP solo para pruebas; en producción Spring selecciona una implementación nativa de DNS
  // y transporte.
  private final AsyncHttpExchange transport =
      (request, maxBytes) ->
          Stages.map(
              http.sendAsync(
                  JdkRequests.request(request),
                  HttpResponse.BodyHandlers.limiting(
                      HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), maxBytes)),
              response ->
                  new AsyncHttpExchange.Response(
                      response.statusCode(),
                      response.headers().firstValue("Content-Type").orElse(null),
                      response.body()));
  private final HttpCoreTest.MutableClock clock = new HttpCoreTest.MutableClock();
  private final AtomicInteger calls = new AtomicInteger();
  private final AtomicReference<String> body =
      new AtomicReference<>("{\"access_token\":\"first-token\",\"expires_in\":300}");
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private final AtomicReference<String> form = new AtomicReference<>();
  private final CountDownLatch arrived = new CountDownLatch(1);
  private final CountDownLatch release = new CountDownLatch(1);
  private final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();
  private volatile boolean hold;
  private volatile int status = 200;
  private HttpServer server;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(handlers);
    server.createContext(
        "/token",
        exchange -> {
          try (exchange) {
            calls.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            form.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            arrived.countDown();
            if (hold && !release.await(5, TimeUnit.SECONDS))
              throw new IllegalStateException("Test response timed out");
            byte[] content = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, content.length);
            exchange.getResponseBody().write(content);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          } catch (IOException cancelled) {
            // La cancelación cierra intencionalmente una respuesta mientras el servidor local la
            // escribe.
          }
        });
    server.start();
  }

  @AfterEach
  void close() {
    release.countDown();
    http.close();
    server.stop(0);
    handlers.close();
  }

  private ClientCredentialsAsyncTokenProvider provider(
      Supplier<CompletionStage<String>> secrets, Duration timeout) {
    return ClientCredentialsAsyncTokenProvider.builder()
        .http(transport)
        .tokenUri(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/token"))
        .clientId("my-client")
        .clientSecret(secrets)
        .scopes(List.of("payments.read", "payments.write"))
        .audience("core")
        .renewBefore(Duration.ofSeconds(60))
        .timeout(timeout)
        .clock(clock)
        .transportSecurity(new TransportSecurityPolicy("test", true))
        .build();
  }

  private AccessToken await(CompletionStage<AccessToken> result) throws Exception {
    return result.toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  @Test
  void concurrentRenewalsShareIoAndLaterRenewalReadsTheRotatedSecret() throws Exception {
    hold = true;
    var secret = new AtomicReference<>("s3cr&t");
    var secretCalls = new AtomicInteger();
    var provider =
        provider(
            () -> {
              secretCalls.incrementAndGet();
              return CompletableFuture.completedFuture(secret.get());
            },
            Duration.ofSeconds(2));
    var results = IntStream.range(0, 40).mapToObj(i -> provider.token()).toList();
    assertTrue(arrived.await(3, TimeUnit.SECONDS));
    assertFalse(results.getFirst().toCompletableFuture().isDone());
    clock.now = clock.now.plusSeconds(5);
    release.countDown();
    AccessToken first = await(results.getFirst());
    for (var result : results) assertSame(first, await(result));
    assertEquals(1, calls.get());
    assertEquals(1, secretCalls.get());
    assertEquals(
        clock.now.plusSeconds(295),
        first.expiresAt(),
        "response latency must not extend token lifetime");
    assertEquals(
        "my-client:s3cr%26t",
        new String(
            Base64.getDecoder().decode(authorization.get().substring(6)), StandardCharsets.UTF_8));
    assertTrue(form.get().contains("scope=payments.read+payments.write"));
    assertTrue(form.get().contains("audience=core"));
    assertSame(first, await(provider.token()));
    secret.set("rotated");
    body.set("{\"access_token\":\"second-token\",\"expires_in\":300,\"token_type\":\"bearer\"}");
    clock.now = clock.now.plusSeconds(250);
    assertEquals("second-token", await(provider.token()).value());
    assertEquals(2, calls.get());
    assertEquals(2, secretCalls.get());
    assertEquals(
        "my-client:rotated",
        new String(
            Base64.getDecoder().decode(authorization.get().substring(6)), StandardCharsets.UTF_8));
  }

  @Test
  void secretLookupIsNativeAndOnlyItsLastCallerCancelsIt() {
    var secret = new CompletableFuture<String>();
    var provider = provider(() -> secret, Duration.ofSeconds(2));
    var first = provider.token().toCompletableFuture();
    var second = provider.token().toCompletableFuture();
    assertNull(http.nativeCall.get());
    first.cancel(true);
    assertFalse(secret.isCancelled());
    second.cancel(true);
    assertTrue(secret.isCancelled());
    assertEquals(0, calls.get());
  }

  @Test
  void lastCancellationReachesTheExactSendAsyncFuture() throws Exception {
    hold = true;
    var provider =
        provider(() -> CompletableFuture.completedFuture("secret"), Duration.ofSeconds(2));
    var first = provider.token().toCompletableFuture();
    var second = provider.token().toCompletableFuture();
    assertTrue(arrived.await(3, TimeUnit.SECONDS));
    first.cancel(true);
    assertFalse(http.nativeCall.get().isCancelled());
    second.cancel(true);
    var nativeFuture = http.nativeCall.get();
    assertTrue(nativeFuture.isDone());
    // HttpClient cancela primero el intercambio; su future puede completarse con una cancelación
    // envuelta antes de que CompletableFuture.cancel pueda marcar su estado directo como cancelado
    // (MinimalFuture del JDK).
    var cancelled = assertThrows(RuntimeException.class, nativeFuture::join);
    assertInstanceOf(
        CancellationException.class,
        cancelled instanceof CompletionException ? cancelled.getCause() : cancelled);
  }

  @Test
  void malformedResponsesDoNotLeakDataOrPoisonTheRenewalCache() throws Exception {
    var provider =
        provider(() -> CompletableFuture.completedFuture("secret"), Duration.ofSeconds(2));
    body.set("sensitive-invalid-json");
    var error = assertThrows(ExecutionException.class, () -> await(provider.token())).getCause();
    assertInstanceOf(IllegalStateException.class, error);
    assertFalse(error.toString().contains("sensitive"));
    assertNull(error.getCause());
    body.set("{\"access_token\":\"recovered\"}");
    assertEquals("recovered", await(provider.token()).value());
    assertEquals(2, calls.get());
  }

  @Test
  void invalidExpiryAndTokenTypesAreRejectedWithoutReflectingTokenValues() {
    for (String extra :
        List.of(
            "\"expires_in\":0",
            "\"expires_in\":-1",
            "\"expires_in\":1.5",
            "\"expires_in\":\"300\"",
            "\"expires_in\":999999999999999999999999999999999",
            "\"token_type\":\"MAC\"")) {
      body.set("{\"access_token\":\"sensitive-token\"," + extra + "}");
      var provider =
          provider(() -> CompletableFuture.completedFuture("secret"), Duration.ofSeconds(2));
      var error = assertThrows(ExecutionException.class, () -> await(provider.token())).getCause();
      assertInstanceOf(IllegalStateException.class, error);
      assertFalse(error.toString().contains("sensitive-token"));
    }
  }

  @Test
  void endpointErrorsRetainTheExternalDependencyContract() {
    status = 401;
    body.set("{\"error\":\"invalid_client\",\"error_description\":\"rejected\"}");
    var provider =
        provider(() -> CompletableFuture.completedFuture("secret"), Duration.ofSeconds(2));
    var error =
        assertInstanceOf(
            ExternalServiceException.class,
            assertThrows(ExecutionException.class, () -> await(provider.token())).getCause());
    assertEquals("invalid_client", error.detail().code());
    assertEquals(502, error.category().httpStatus());
  }

  @Test
  void expiredResponsesAreRejectedAndDoNotPoisonLaterRenewal() throws Exception {
    hold = true;
    body.set("{\"access_token\":\"expired\",\"expires_in\":1}");
    var provider =
        provider(() -> CompletableFuture.completedFuture("secret"), Duration.ofSeconds(2));
    var pending = provider.token();
    assertTrue(arrived.await(3, TimeUnit.SECONDS));
    clock.now = clock.now.plusSeconds(2);
    release.countDown();
    assertInstanceOf(
        IllegalStateException.class,
        assertThrows(ExecutionException.class, () -> await(pending)).getCause());
    body.set("{\"access_token\":\"valid\",\"expires_in\":300}");
    assertEquals("valid", await(provider.token()).value());
  }

  @Test
  void invalidDurationsFailBeforeCredentialLookup() {
    for (String endpoint :
        List.of(
            "ftp://example.test/token",
            "/relative",
            "https://user:pass@example.test/token",
            "https://example.test/token#fragment")) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              ClientCredentialsAsyncTokenProvider.builder()
                  .http(transport)
                  .tokenUri(URI.create(endpoint))
                  .clientId("client")
                  .clientSecret(
                      () -> {
                        throw new AssertionError("No credentials on invalid configuration");
                      })
                  .scopes(List.of())
                  .audience(null)
                  .renewBefore(Duration.ZERO)
                  .timeout(Duration.ofSeconds(2))
                  .clock(clock)
                  .transportSecurity(new TransportSecurityPolicy("test", true))
                  .build());
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            provider(
                () -> {
                  throw new AssertionError("No I/O during validation");
                },
                Duration.ZERO));
    var uri = URI.create("http://127.0.0.1/token");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ClientCredentialsAsyncTokenProvider.builder()
                .http(transport)
                .tokenUri(uri)
                .clientId("client")
                .clientSecret(() -> CompletableFuture.completedFuture("secret"))
                .scopes(List.of())
                .audience(null)
                .renewBefore(Duration.ofSeconds(-1))
                .timeout(Duration.ofSeconds(2))
                .clock(clock)
                .transportSecurity(new TransportSecurityPolicy("test", true))
                .build());
    assertFalse(
        new AsyncHttpExchange.Response(200, null, "sensitive-body")
            .toString()
            .contains("sensitive-body"));
  }

  @Test
  void responseSizeAndTimeoutAreBounded() {
    body.set("x".repeat(70_000));
    var bounded =
        provider(() -> CompletableFuture.completedFuture("secret"), Duration.ofSeconds(2));
    var tooLarge = assertThrows(ExecutionException.class, () -> await(bounded.token())).getCause();
    assertInstanceOf(ExternalServiceException.class, tooLarge);
    hold = true;
    var timed = provider(() -> CompletableFuture.completedFuture("secret"), Duration.ofMillis(100));
    var timeout = assertThrows(ExecutionException.class, () -> await(timed.token())).getCause();
    assertInstanceOf(ExternalServiceException.class, timeout);
    assertInstanceOf(HttpTimeoutException.class, timeout.getCause());
    assertTrue(http.nativeCall.get().isDone());
  }

  private static final class TrackingClient extends HttpClient {
    private final HttpClient delegate = HttpClient.newHttpClient();
    private final AtomicReference<CompletableFuture<?>> nativeCall = new AtomicReference<>();

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      throw new AssertionError("Synchronous HTTP is forbidden");
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      var future = delegate.sendAsync(request, handler);
      nativeCall.set(future);
      return future;
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        HttpResponse.BodyHandler<T> handler,
        HttpResponse.PushPromiseHandler<T> pushes) {
      throw new AssertionError("Unexpected push handler");
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
      return delegate.cookieHandler();
    }

    @Override
    public Optional<Duration> connectTimeout() {
      return delegate.connectTimeout();
    }

    @Override
    public Redirect followRedirects() {
      return delegate.followRedirects();
    }

    @Override
    public Optional<ProxySelector> proxy() {
      return delegate.proxy();
    }

    @Override
    public SSLContext sslContext() {
      return delegate.sslContext();
    }

    @Override
    public SSLParameters sslParameters() {
      return delegate.sslParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
      return delegate.authenticator();
    }

    @Override
    public Version version() {
      return delegate.version();
    }

    @Override
    public Optional<Executor> executor() {
      return delegate.executor();
    }

    @Override
    public void close() {
      delegate.close();
    }
  }
}
