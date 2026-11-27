package pe.axiz.spring.httpclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslStoreBundle;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import pe.axiz.core.commons.net.TransportSecurityPolicy;
import pe.axiz.core.errors.ExternalServiceException;
import pe.axiz.core.http.AuthSettings;
import pe.axiz.core.http.AuthType;
import pe.axiz.core.http.ClientSettings;
import pe.axiz.core.resilience.TimeoutSettings;
import pe.axiz.core.secrets.AsyncSecretResolver;
import pe.axiz.core.secrets.SecretResolver;
import pe.axiz.core.secrets.SecretValue;
import pe.axiz.spring.foundation.ReactiveStages;
import pe.axiz.spring.test.BlockHoundExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

@ExtendWith(BlockHoundExtension.class)
@Timeout(45)
class NativeHttpCredentialsTest {
  private final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();
  private final AtomicInteger calls = new AtomicInteger();
  private final AtomicInteger redirected = new AtomicInteger();
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private volatile int status = 200;
  private volatile String body;
  private HttpServer server;

  @BeforeEach
  void start() throws Exception {
    // La URI de tokens usa localhost: la primera solicitud también ejercita el resolvedor nativo.
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(handlers);
    server.createContext(
        "/token",
        exchange -> {
          try (exchange) {
            int number = calls.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes();
            byte[] response =
                (body != null
                        ? body
                        : "{\"access_token\":\"token-" + number + "\",\"expires_in\":300}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (status == 302) exchange.getResponseHeaders().set("Location", "/redirected");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
          }
        });
    server.createContext(
        "/redirected",
        exchange -> {
          redirected.incrementAndGet();
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void close() {
    server.stop(0);
    handlers.close();
    assertTrue(
        BlockHoundExtension.detectedBlockingCalls().isEmpty(),
        () -> BlockHoundExtension.detectedBlockingCalls().toString());
  }

  private ClientSettings settings(AuthType type) {
    return ClientSettings.builder()
        .name("bank")
        .baseUrl(URI.create("http://localhost:" + server.getAddress().getPort()))
        .timeouts(
            new TimeoutSettings(
                Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(5)))
        .auth(
            type == AuthType.API_KEY
                ? new AuthSettings(type, null, null, null, List.of(), null, "X-Api-Key", "api")
                : new AuthSettings(
                    type,
                    URI.create("http://localhost:" + server.getAddress().getPort() + "/token"),
                    "client",
                    "oauth",
                    List.of("read"),
                    null,
                    null,
                    null))
        .retry(ClientSettings.RetryMode.NONE)
        .maxAttempts(1)
        .circuitBreaker(null)
        .maxConcurrentCalls(1)
        .propagateHeaders(List.of())
        .maxConnections(2)
        .transportSecurity(new TransportSecurityPolicy("test", true))
        .build();
  }

  @Test
  void coldNativeLookupCoalescesAndRenewalReadsTheRotatedSecretWithItsParent() {
    var clock = new MovingClock();
    var current = new AtomicReference<>("first-secret");
    var secretCalls = new AtomicInteger();
    var observations = ObservationRegistry.create();
    var stopped = new ConcurrentLinkedQueue<Observation.Context>();
    observations
        .observationConfig()
        .observationHandler(
            new ObservationHandler<>() {
              public boolean supportsContext(Observation.Context context) {
                return true;
              }

              public void onStop(Observation.Context context) {
                stopped.add(context);
              }
            });
    var parent = Observation.start("request", observations);
    AsyncSecretResolver secrets =
        name -> {
          assertTrue(Schedulers.isInNonBlockingThread(), Thread.currentThread().getName());
          assertSame(parent, observations.getCurrentObservation());
          secretCalls.incrementAndGet();
          return CompletableFuture.completedFuture(Optional.of(SecretValue.of(current.get())));
        };
    try (var auth =
        OutboundAuth.createAsync(
            settings(AuthType.CLIENT_CREDENTIALS),
            secrets,
            Duration.ofSeconds(60),
            clock,
            observations)) {
      StepVerifier.create(
              Flux.range(0, 40)
                  .flatMap(i -> ReactiveStages.from(auth::headers), 20)
                  .doOnNext(
                      headers -> {
                        assertEquals("Bearer token-1", headers.get("Authorization").reveal());
                        assertTrue(!headers.toString().contains("token-1"));
                        assertSame(parent, observations.getCurrentObservation());
                      })
                  .contextWrite(Context.of(ObservationThreadLocalAccessor.KEY, parent))
                  .subscribeOn(Schedulers.parallel()))
          .expectNextCount(40)
          .verifyComplete();
      assertEquals(1, calls.get());
      assertEquals(1, secretCalls.get());
      current.set("rotated-secret");
      clock.now = clock.now.plusSeconds(250);
      StepVerifier.create(
              ReactiveStages.from(auth::headers)
                  .contextWrite(Context.of(ObservationThreadLocalAccessor.KEY, parent))
                  .subscribeOn(Schedulers.parallel()))
          .assertNext(
              headers -> assertEquals("Bearer token-2", headers.get("Authorization").reveal()))
          .verifyComplete();
      assertEquals(2, calls.get());
      assertEquals(2, secretCalls.get());
      assertEquals(
          "client:rotated-secret",
          new String(
              Base64.getDecoder().decode(authorization.get().substring(6)),
              StandardCharsets.UTF_8));
      assertEquals(2, stopped.size());
      for (var http : stopped) assertSame(parent, http.getParentObservation());
      assertNull(observations.getCurrentObservation());
    } finally {
      parent.stop();
    }
  }

  @Test
  void tlsUsesTheConfiguredBundleForColdConnectionsAndRejectsWrongHostsAndUntrustedPeers(
      @TempDir Path temporary) throws Exception {
    var store = temporary.resolve("oauth.p12");
    var output = temporary.resolve("keytool.log");
    var process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair",
                "-alias",
                "oauth",
                "-keyalg",
                "EC",
                "-groupname",
                "secp256r1",
                "-dname",
                "CN=localhost",
                "-ext",
                "SAN=dns:localhost",
                "-validity",
                "3",
                "-storetype",
                "PKCS12",
                "-keystore",
                store.toString(),
                "-storepass",
                "changeit")
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    try {
      assertTrue(process.waitFor(20, TimeUnit.SECONDS));
      assertEquals(0, process.exitValue(), Files.readString(output));
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
    var keys = KeyStore.getInstance("PKCS12");
    try (var input = Files.newInputStream(store)) {
      keys.load(input, "changeit".toCharArray());
    }
    var keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keyManagers.init(keys, "changeit".toCharArray());
    var ssl = SSLContext.getInstance("TLSv1.3");
    ssl.init(keyManagers.getKeyManagers(), null, null);
    var trust = KeyStore.getInstance("PKCS12");
    trust.load(null, null);
    trust.setCertificateEntry("oauth", keys.getCertificate("oauth"));
    var bundle = SslBundle.of(SslStoreBundle.of(null, null, trust));
    var secure = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    secure.setHttpsConfigurator(new HttpsConfigurator(ssl));
    secure.setExecutor(handlers);
    var successful = new AtomicInteger();
    secure.createContext(
        "/token",
        exchange -> {
          try (exchange) {
            exchange.getRequestBody().readAllBytes();
            successful.incrementAndGet();
            var response =
                "{\"access_token\":\"tls-token\",\"expires_in\":300}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Connection", "close");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
          }
        });
    secure.start();
    var clock = new MovingClock();
    AsyncSecretResolver secrets =
        name -> CompletableFuture.completedFuture(Optional.of(SecretValue.of("secret")));
    var trustedSettings =
        tokenSettings(URI.create("https://localhost:" + secure.getAddress().getPort() + "/token"));
    var wrongSettings =
        tokenSettings(URI.create("https://127.0.0.1:" + secure.getAddress().getPort() + "/token"));
    try (var trusted =
            OutboundAuth.createAsync(
                trustedSettings,
                secrets,
                Duration.ofSeconds(60),
                clock,
                ObservationRegistry.NOOP,
                bundle);
        var wrongHost =
            OutboundAuth.createAsync(
                wrongSettings, secrets, Duration.ZERO, clock, ObservationRegistry.NOOP, bundle);
        var untrusted = OutboundAuth.createAsync(trustedSettings, secrets, Duration.ZERO, clock)) {
      Files.delete(store);
      for (int index = 0; index < 2; index++) {
        StepVerifier.create(
                ReactiveStages.from(trusted::headers).subscribeOn(Schedulers.parallel()))
            .assertNext(
                headers -> assertEquals("Bearer tls-token", headers.get("Authorization").reveal()))
            .expectComplete()
            .verify(Duration.ofSeconds(10));
        clock.now = clock.now.plusSeconds(250);
      }
      StepVerifier.create(
              ReactiveStages.from(wrongHost::headers).subscribeOn(Schedulers.parallel()))
          .expectErrorSatisfies(NativeHttpCredentialsTest::assertHandshakeFailure)
          .verify(Duration.ofSeconds(10));
      StepVerifier.create(
              ReactiveStages.from(untrusted::headers).subscribeOn(Schedulers.parallel()))
          .expectErrorSatisfies(NativeHttpCredentialsTest::assertHandshakeFailure)
          .verify(Duration.ofSeconds(10));
      assertEquals(2, successful.get());
    } finally {
      secure.stop(0);
    }
  }

  private static void assertHandshakeFailure(Throwable error) {
    assertTrue(error instanceof ExternalServiceException);
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof SSLHandshakeException) return;
    }
    throw new AssertionError("Expected strict TLS handshake rejection", error);
  }

  private ClientSettings tokenSettings(URI uri) {
    var original = settings(AuthType.CLIENT_CREDENTIALS);
    var a = original.auth();
    return ClientSettings.builder()
        .name(original.name())
        .baseUrl(original.baseUrl())
        .timeouts(original.timeouts())
        .auth(
            new AuthSettings(
                a.type(),
                uri,
                a.clientId(),
                a.clientSecretRef(),
                a.scopes(),
                a.audience(),
                null,
                null))
        .retry(original.retry())
        .maxAttempts(original.maxAttempts())
        .circuitBreaker(original.circuitBreaker())
        .maxConcurrentCalls(original.maxConcurrentCalls())
        .propagateHeaders(original.propagateHeaders())
        .maxConnections(original.maxConnections())
        .transportSecurity(new TransportSecurityPolicy("test", true))
        .build();
  }

  @Test
  void tokenRedirectsAreNotFollowedAndOversizedBodiesFailBoundedly() {
    AsyncSecretResolver secrets =
        name -> CompletableFuture.completedFuture(Optional.of(SecretValue.of("secret")));
    status = 302;
    try (var auth =
        OutboundAuth.createAsync(
            settings(AuthType.CLIENT_CREDENTIALS), secrets, Duration.ZERO, Clock.systemUTC())) {
      StepVerifier.create(ReactiveStages.from(auth::headers).subscribeOn(Schedulers.parallel()))
          .expectError(ExternalServiceException.class)
          .verify(Duration.ofSeconds(10));
      assertEquals(0, redirected.get());
      status = 200;
      body = "x".repeat(70_000);
      StepVerifier.create(ReactiveStages.from(auth::headers).subscribeOn(Schedulers.parallel()))
          .expectError(ExternalServiceException.class)
          .verify(Duration.ofSeconds(10));
      assertEquals(2, calls.get());
    }
  }

  @Test
  void apiKeyPreparationIsNativeAndCancellationReturnsItsBulkheadPermit() throws Exception {
    var sent = new CountDownLatch(1);
    var cancelled = new CountDownLatch(1);
    var settings = settings(AuthType.API_KEY);
    var resilience = new ClientResilience(settings, CircuitBreakerRegistry.ofDefaults());
    AsyncSecretResolver secrets =
        name -> {
          assertTrue(Schedulers.isInNonBlockingThread());
          return CompletableFuture.completedFuture(Optional.of(SecretValue.of("native-api-key")));
        };
    try (var auth = OutboundAuth.createAsync(settings, secrets, Duration.ZERO, Clock.systemUTC())) {
      var filter = new AxizExchangeFilter(resilience, auth, null);
      StepVerifier.create(
              filter
                  .filter(
                      ClientRequest.create(HttpMethod.POST, settings.baseUrl()).build(),
                      request -> {
                        assertEquals("native-api-key", request.headers().getFirst("X-Api-Key"));
                        return Mono.<ClientResponse>never()
                            .doOnRequest(ignored -> sent.countDown())
                            .doOnCancel(cancelled::countDown);
                      })
                  .subscribeOn(Schedulers.parallel()))
          .then(
              () -> {
                try {
                  assertTrue(sent.await(3, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                  throw new AssertionError(e);
                }
              })
          .thenCancel()
          .verify();
      assertTrue(cancelled.await(3, TimeUnit.SECONDS));
      resilience.acquire();
      resilience.release(System.nanoTime(), null, 200);
    }
  }

  @Test
  void reactiveStartupRejectsSynchronousOnlyCredentialsAndAcceptsTheNativePort() {
    var synchronousCalls = new AtomicInteger();
    SecretResolver blocking =
        name -> {
          synchronousCalls.incrementAndGet();
          throw new AssertionError("No synchronous fallback");
        };
    var runner =
        new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AxizHttpClientAutoConfiguration.class))
            .withPropertyValues(
                "axiz.http-client.clients.bank.base-url=https://bank.example.test",
                "axiz.http-client.clients.bank.auth.type=API_KEY",
                "axiz.http-client.clients.bank.auth.api-key-header=X-Api-Key",
                "axiz.http-client.clients.bank.auth.api-key-ref=api")
            .withBean(SecretResolver.class, () -> blocking);
    runner.run(
        context ->
            assertThat(context)
                .hasFailed()
                .getFailure()
                .hasMessageContaining("AsyncSecretResolver"));
    runner
        .withBean(
            AsyncSecretResolver.class,
            () -> name -> CompletableFuture.completedFuture(Optional.of(SecretValue.of("native"))))
        .run(context -> assertThat(context).hasNotFailed());
    assertEquals(0, synchronousCalls.get());
  }

  @Test
  void cancellingApiKeyLookupCancelsThePendingSecretAndClosedOwnersRejectRequests() {
    var pending = new CompletableFuture<Optional<SecretValue>>();
    var auth =
        OutboundAuth.createAsync(
            settings(AuthType.API_KEY), name -> pending, Duration.ZERO, Clock.systemUTC());
    StepVerifier.create(ReactiveStages.from(auth::headers)).thenCancel().verify();
    assertTrue(pending.isCancelled());
    auth.close();
    auth.close();
    StepVerifier.create(ReactiveStages.from(auth::headers))
        .expectError(IllegalStateException.class)
        .verify();
  }

  @Test
  void servletSecretsAreClosedEvenWhenHeaderApplicationFails() {
    var secret = SecretValue.of("CREDENTIAL-CANARY");
    try (var auth =
        OutboundAuth.create(
            settings(AuthType.API_KEY),
            name -> Optional.of(secret),
            Duration.ZERO,
            Clock.systemUTC())) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              auth.apply(
                  (name, value) -> {
                    assertEquals("CREDENTIAL-CANARY", value);
                    throw new IllegalArgumentException("request rejected");
                  }));
      assertThrows(IllegalStateException.class, secret::reveal);
    }
  }

  @Test
  void oauthSecretIsClosedInBothExecutionStacks() {
    for (boolean nativeIo : new boolean[] {false, true}) {
      var secret = SecretValue.of("secret");
      try (var auth =
          nativeIo
              ? OutboundAuth.createAsync(
                  settings(AuthType.CLIENT_CREDENTIALS),
                  name -> CompletableFuture.completedFuture(Optional.of(secret)),
                  Duration.ZERO,
                  Clock.systemUTC())
              : OutboundAuth.create(
                  settings(AuthType.CLIENT_CREDENTIALS),
                  name -> Optional.of(secret),
                  Duration.ZERO,
                  Clock.systemUTC())) {
        if (nativeIo)
          auth.headers().toCompletableFuture().join().values().forEach(SecretValue::close);
        else auth.apply((name, value) -> assertTrue(value.startsWith("Bearer ")));
        assertThrows(IllegalStateException.class, secret::reveal);
      }
    }
  }

  @Test
  void nativeApiKeyClosesOriginalAndLateCancelledSecretsButTransfersAnIndependentHeader() {
    var original = SecretValue.of("CREDENTIAL-CANARY");
    try (var auth =
        OutboundAuth.createAsync(
            settings(AuthType.API_KEY),
            name -> CompletableFuture.completedFuture(Optional.of(original)),
            Duration.ZERO,
            Clock.systemUTC())) {
      var headers = auth.headers().toCompletableFuture().join();
      assertThrows(IllegalStateException.class, original::reveal);
      try (var header = headers.get("X-Api-Key")) {
        assertEquals("CREDENTIAL-CANARY", header.reveal());
      }
    }
    var late =
        new CompletableFuture<Optional<SecretValue>>() {
          @Override
          public boolean cancel(boolean interrupt) {
            return false;
          }
        };
    try (var auth =
        OutboundAuth.createAsync(
            settings(AuthType.API_KEY), name -> late, Duration.ZERO, Clock.systemUTC())) {
      var answer = auth.headers().toCompletableFuture();
      assertTrue(answer.cancel(true));
      var secret = SecretValue.of("LATE-CANARY");
      late.complete(Optional.of(secret));
      assertThrows(IllegalStateException.class, secret::reveal);
    }
  }

  private static final class MovingClock extends Clock {
    volatile Instant now = Instant.now();

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return Clock.fixed(now, zone);
    }

    public Instant instant() {
      return now;
    }
  }
}
