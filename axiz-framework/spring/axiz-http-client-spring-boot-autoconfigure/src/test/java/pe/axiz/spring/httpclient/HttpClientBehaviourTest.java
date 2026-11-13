package pe.axiz.spring.httpclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.commons.RequestMetadata;
import pe.axiz.core.commons.net.TransportSecurityPolicy;
import pe.axiz.core.errors.ExternalServiceException;
import pe.axiz.core.http.AuthSettings;
import pe.axiz.core.http.AuthType;
import pe.axiz.core.http.ClientSettings;
import pe.axiz.core.resilience.TimeoutSettings;
import pe.axiz.core.secrets.SecretNotFoundException;
import pe.axiz.core.secrets.SecretResolver;
import pe.axiz.core.secrets.SecretValue;
import reactor.core.publisher.Mono;

/** The configured clients against a local server: headers, credentials, retries and errors. */
class HttpClientBehaviourTest {

  private HttpServer server;
  private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
  private final Map<String, String> lastHeaders = new ConcurrentHashMap<>();

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          int n = calls.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
          exchange
              .getRequestHeaders()
              .forEach((k, v) -> lastHeaders.put(k.toLowerCase(Locale.ROOT), v.get(0)));
          int status;
          String body;
          String type = "application/json";
          switch (path) {
            case "/token" -> {
              status = 200;
              body = "{\"access_token\":\"token-" + n + "\",\"expires_in\":300}";
            }
            case "/flaky" -> {
              status = n < 2 ? 503 : 200;
              body = "{\"ok\":true}";
            }
            case "/down" -> {
              status = 503;
              body = "";
            }
            case "/rejected" -> {
              status = 422;
              body = "{\"code\":\"LIMIT\",\"message\":\"daily limit\"}";
            }
            default -> {
              status = 200;
              body = "{\"ok\":true}";
            }
          }
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", type);
          exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
          if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
          }
          exchange.close();
        });
    server.start();
  }

  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void actualClientBuildersKeepTemplatesAndNeverLogExpandedPathsOrQueryValues(
      CapturedOutput output) {
    String identifier = UUID.randomUUID().toString();
    String secret = UUID.randomUUID().toString();
    runner("axiz.http-client.clients.bank.base-url=" + base())
        .run(
            ctx -> {
              var bank = ctx.getBean(AxizHttpClients.class).restClient("bank");
              assertEquals(
                  "{\"ok\":true}",
                  bank.get()
                      .uri("/customers/{id}?credential={secret}", identifier, secret)
                      .retrieve()
                      .body(String.class));
              bank.get()
                  .uri(URI.create(base() + "/customers/" + identifier + "?credential=" + secret))
                  .retrieve()
                  .toBodilessEntity();
              var registry = ctx.getBean(HttpClientRegistry.class);
              var settings = registry.find("bank").orElseThrow();
              var reactive =
                  WebClient.builder()
                      .baseUrl(base())
                      .filter(
                          new AxizExchangeFilter(
                              registry.resilience(settings),
                              registry.asyncAuth(settings),
                              registry.tenantHeader()))
                      .build();
              assertEquals(
                  "{\"ok\":true}",
                  reactive
                      .get()
                      .uri("/customers/{id}?credential={secret}", identifier, secret)
                      .retrieve()
                      .bodyToMono(String.class)
                      .block(Duration.ofSeconds(5)));
              reactive
                  .get()
                  .uri(URI.create(base() + "/customers/" + identifier + "?credential=" + secret))
                  .retrieve()
                  .toBodilessEntity()
                  .block(Duration.ofSeconds(5));
            });
    assertThat(output.getOut())
        .contains("path=/customers/{id}", "path=127.0.0.1 [no-template]")
        .doesNotContain(identifier, secret, "credential=");
  }

  @Test
  void managedRestClientNeverFollowsRedirectsOrLeaksCredentialsToAbsoluteOrigins() {
    var redirected = new AtomicInteger();
    server.createContext(
        "/redirect",
        exchange -> {
          exchange.getResponseHeaders().set("Location", base() + "/destination");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    server.createContext(
        "/destination",
        exchange -> {
          redirected.incrementAndGet();
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    runner(
            "axiz.http-client.clients.bank.base-url=" + base(),
            "axiz.http-client.clients.bank.auth.type=API_KEY",
            "axiz.http-client.clients.bank.auth.api-key-ref=bank-api-key",
            "axiz.http-client.clients.bank.auth.api-key-header=X-Api-Key")
        .run(
            context -> {
              var registry = context.getBean(HttpClientRegistry.class);
              assertEquals(
                  HttpRedirects.DONT_FOLLOW,
                  registry.transport(registry.find("bank").orElseThrow()).redirects());
              var client = context.getBean(AxizHttpClients.class).restClient("bank");
              assertEquals(
                  302,
                  client
                      .get()
                      .uri("/redirect")
                      .retrieve()
                      .toBodilessEntity()
                      .getStatusCode()
                      .value());
              assertEquals(0, redirected.get());
              assertThrows(
                  IllegalArgumentException.class,
                  () ->
                      client
                          .get()
                          .uri("https://outside.invalid/secret")
                          .retrieve()
                          .toBodilessEntity());
            });
  }

  @Test
  void plaintextClientsFailAtStartupInProductionEvenWithoutAuthentication() {
    runner(
            "axiz.config.environment=prod",
            "axiz.transport.allow-plain-http=true",
            "axiz.http-client.clients.bank.base-url=" + base())
        .run(context -> assertThat(context).hasFailed());
  }

  @AfterEach
  void stop() {
    server.stop(0);
    SecurityContextHolder.clearContext();
  }

  private String base() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private static final SecretResolver SECRETS =
      name ->
          switch (name) {
            case "bank-api-key" -> Optional.of(SecretValue.of("k-123"));
            case "bank-client-secret" -> Optional.of(SecretValue.of("s3cret"));
            default -> Optional.empty();
          };

  private ApplicationContextRunner runner(String... properties) {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizHttpClientAutoConfiguration.class))
        .withBean(SecretResolver.class, () -> SECRETS)
        .withPropertyValues("axiz.config.environment=test", "axiz.transport.allow-plain-http=true")
        .withPropertyValues(properties);
  }

  @Test
  void aRestClientCarriesTheRequestContextAndItsApiKeyAndRetriesIdempotentCalls() {
    runner(
            "axiz.http-client.clients.bank.base-url=" + base(),
            "axiz.http-client.clients.bank.propagate-headers=X-Channel",
            "axiz.http-client.clients.bank.auth.type=API_KEY",
            "axiz.http-client.clients.bank.auth.api-key-header=X-Api-Key",
            "axiz.http-client.clients.bank.auth.api-key-ref=bank-api-key",
            "axiz.tenancy.enabled=true")
        .run(
            ctx -> {
              RestClient bank = ctx.getBean(AxizHttpClients.class).restClient("bank");
              RequestMetadata m =
                  RequestMetadata.builder("corr-12345678", "req-12345678")
                      .tenantId("BANK-A")
                      .attributes(Map.of("X-Channel", "mobile", "X-Internal", "no"))
                      .build();
              try (RequestContext.Scope ignored = RequestContext.open(m)) {
                assertEquals(
                    "{\"ok\":true}", bank.get().uri("/flaky").retrieve().body(String.class));
              }
              assertEquals(2, calls.get("/flaky").get(), "a 503 on a GET is retried once");
              assertEquals("corr-12345678", lastHeaders.get("x-correlation-id"));
              assertEquals("mobile", lastHeaders.get("x-channel"));
              assertEquals("bank-a", lastHeaders.get("x-tenant-id"));
              assertEquals("k-123", lastHeaders.get("x-api-key"));
              assertFalse(lastHeaders.containsKey("x-internal"));

              ExternalServiceException rejected =
                  assertThrows(
                      ExternalServiceException.class,
                      () -> bank.post().uri("/rejected").retrieve().body(String.class));
              assertEquals("bank", rejected.detail().system());
              assertEquals(422, rejected.detail().status());

              assertThrows(
                  ExternalServiceException.class,
                  () -> bank.post().uri("/down").retrieve().body(String.class));
              assertEquals(
                  1, calls.get("/down").get(), "a POST without idempotency key is not retried");

              assertThrows(
                  IllegalArgumentException.class,
                  () -> ctx.getBean(AxizHttpClients.class).restClient("unknown"));
            });
  }

  @Test
  void clientCredentialsFetchAndReuseATokenFromTheSecretStore() {
    runner(
            "axiz.http-client.clients.core.base-url=" + base(),
            "axiz.http-client.clients.core.auth.type=CLIENT_CREDENTIALS",
            "axiz.http-client.clients.core.auth.token-uri=" + base() + "/token",
            "axiz.http-client.clients.core.auth.client-id=payments",
            "axiz.http-client.clients.core.auth.client-secret-ref=bank-client-secret",
            "axiz.http-client.clients.core.auth.scopes=core.read")
        .run(
            ctx -> {
              RestClient core = ctx.getBean(AxizHttpClients.class).restClient("core");
              core.get().uri("/accounts").retrieve().toBodilessEntity();
              assertEquals("Bearer token-1", lastHeaders.get("authorization"));
              core.get().uri("/accounts").retrieve().toBodilessEntity();
              assertEquals("Bearer token-1", lastHeaders.get("authorization"), "token reused");
              assertEquals(1, calls.get("/token").get());
            });
  }

  private static ClientSettings settings(
      String name, AuthSettings auth, @Nullable String circuitBreaker) {
    return new ClientSettings(
        name,
        URI.create("https://bank.example.com"),
        new TimeoutSettings(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(10)),
        auth,
        ClientSettings.RetryMode.IDEMPOTENT_ONLY,
        3,
        circuitBreaker,
        0,
        List.of(),
        10,
        TransportSecurityPolicy.system());
  }

  @Test
  void credentialsNeedTheSecretStoreAndAMissingSecretIsNamed() {
    AuthSettings apiKey =
        new AuthSettings(AuthType.API_KEY, null, null, null, List.of(), null, "X-Api-Key", "nope");
    IllegalStateException noStore =
        assertThrows(
            IllegalStateException.class,
            () ->
                OutboundAuth.create(
                    settings("bank", apiKey, "default"),
                    null,
                    Duration.ofSeconds(30),
                    Clock.systemUTC()));
    assertThat(noStore).hasMessageContaining("axiz.http-client.clients.bank.auth.type=API_KEY");

    OutboundAuth missing =
        OutboundAuth.create(
            settings("bank", apiKey, "default"),
            SECRETS,
            Duration.ofSeconds(30),
            Clock.systemUTC());
    assertThrows(SecretNotFoundException.class, () -> missing.apply((k, v) -> {}));
  }

  @Test
  void servletTokenRelaySendsTheCallersBearerToken() {
    AuthSettings relay =
        new AuthSettings(AuthType.TOKEN_RELAY, null, null, null, List.of(), null, null, null);
    OutboundAuth auth =
        OutboundAuth.create(
            settings("crud", relay, "default"), null, Duration.ofSeconds(30), Clock.systemUTC());
    AtomicReference<String> header = new AtomicReference<>();
    auth.apply((k, v) -> header.set(v));
    assertNull(header.get(), "no caller, nothing relayed");
    OAuth2AccessToken token =
        new OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER,
            "caller-token",
            Instant.now(),
            Instant.now().plusSeconds(300));
    SecurityContextHolder.getContext()
        .setAuthentication(
            new BearerTokenAuthentication(
                new DefaultOAuth2AuthenticatedPrincipal(Map.of("sub", "C-1"), List.of()),
                token,
                List.of()));
    auth.apply((k, v) -> header.set(v));
    assertEquals("Bearer caller-token", header.get());
    assertTrue(auth.relay());
  }

  @Test
  void anOpenCircuitFailsFastAndIsNeverRetried() {
    CircuitBreakerRegistry registry =
        CircuitBreakerRegistry.of(
            Map.of(
                "strict",
                CircuitBreakerConfig.custom()
                    .slidingWindowSize(2)
                    .minimumNumberOfCalls(2)
                    .failureRateThreshold(50)
                    .build()));
    ClientResilience strict =
        new ClientResilience(settings("bank", AuthSettings.none(), "strict"), registry);
    long start = System.nanoTime();
    strict.acquire();
    strict.release(start, null, 503);
    strict.acquire();
    strict.release(start, new IOException("reset"), -1);
    ExternalServiceException open = assertThrows(ExternalServiceException.class, strict::acquire);
    assertEquals("circuit-open", open.detail().code());
    assertFalse(strict.retry("GET", null, open, -1, 1, start));

    ClientResilience none =
        new ClientResilience(settings("bank", AuthSettings.none(), null), registry);
    none.acquire();
    none.release(start, new IOException("reset"), -1);
    none.acquire(); // without a circuit breaker nothing ever opens

    assertTrue(ClientResilience.retryableStatus(429));
    assertFalse(ClientResilience.retryableStatus(500));
  }

  @Test
  void aFullBulkheadRejectsAndRetriesStayWithinTheirRulesAndBudget() {
    ClientSettings oneAtATime =
        new ClientSettings(
            "ledger",
            URI.create("https://ledger.example.com"),
            new TimeoutSettings(
                Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(1)),
            AuthSettings.none(),
            ClientSettings.RetryMode.IDEMPOTENT_ONLY,
            3,
            "not-configured",
            1,
            List.of(),
            10,
            TransportSecurityPolicy.system());
    ClientResilience ledger = new ClientResilience(oneAtATime, CircuitBreakerRegistry.ofDefaults());
    long start = System.nanoTime();
    ledger.acquire();
    ExternalServiceException full = assertThrows(ExternalServiceException.class, ledger::acquire);
    assertEquals("bulkhead-full", full.detail().code());
    assertFalse(ledger.retry("GET", null, full, -1, 1, start), "a full bulkhead is not retried");
    ledger.release(start, null, 200);
    ledger.acquire();
    ledger.release(start, null, 429);

    assertTrue(ledger.retry("GET", null, null, 503, 1, start), "a 503 on a GET is retried");
    assertTrue(ledger.retry("POST", "key-00000001", null, 503, 1, start), "with a key too");
    assertFalse(ledger.retry("POST", null, null, 503, 1, start), "never a bare POST");
    assertFalse(ledger.retry("GET", null, null, 503, 3, start), "no attempts left");
    assertFalse(
        ledger.retry("GET", null, null, 503, 1, start - Duration.ofSeconds(5).toNanos()),
        "no time left in the total budget");

    ClientSettings noRetries =
        new ClientSettings(
            "ledger",
            URI.create("https://ledger.example.com"),
            new TimeoutSettings(
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(10)),
            AuthSettings.none(),
            ClientSettings.RetryMode.NONE,
            3,
            null,
            0,
            List.of(),
            10,
            TransportSecurityPolicy.system());
    assertFalse(
        new ClientResilience(noRetries, CircuitBreakerRegistry.ofDefaults())
            .retry("GET", null, null, 503, 1, System.nanoTime()));
  }

  @Test
  void theReactiveFilterRetriesIdempotentCallsAndTranslatesErrors() {
    ClientSettings s = settings("bank", AuthSettings.none(), "default");
    AxizExchangeFilter filter =
        new AxizExchangeFilter(
            new ClientResilience(s, CircuitBreakerRegistry.ofDefaults()),
            OutboundAuth.create(s, null, Duration.ofSeconds(30), Clock.systemUTC()),
            "X-Tenant-Id");
    AtomicInteger attempts = new AtomicInteger();
    AtomicReference<ClientRequest> sent = new AtomicReference<>();
    ExchangeFunction flaky =
        req -> {
          sent.set(req);
          return Mono.just(
              ClientResponse.create(
                      attempts.incrementAndGet() < 2
                          ? HttpStatus.SERVICE_UNAVAILABLE
                          : HttpStatus.OK)
                  .build());
        };
    RequestMetadata m =
        RequestMetadata.builder("corr-12345678", "req-12345678").tenantId("bank-b").build();
    ClientResponse ok =
        filter
            .filter(
                ClientRequest.create(HttpMethod.GET, URI.create("https://bank.example.com/x"))
                    .build(),
                flaky)
            .contextWrite(c -> c.put(RequestContext.KEY, m))
            .block(Duration.ofSeconds(10));
    assertEquals(200, ok.statusCode().value());
    assertEquals(2, attempts.get());
    assertEquals("corr-12345678", sent.get().headers().getFirst("X-Correlation-Id"));
    assertEquals("bank-b", sent.get().headers().getFirst("X-Tenant-Id"));

    ExchangeFunction rejecting =
        req ->
            Mono.just(
                ClientResponse.create(HttpStatus.UNPROCESSABLE_CONTENT)
                    .header("Content-Type", "application/json")
                    .body("{\"code\":\"LIMIT\"}")
                    .build());
    ExternalServiceException e =
        assertThrows(
            ExternalServiceException.class,
            () ->
                filter
                    .filter(
                        ClientRequest.create(
                                HttpMethod.POST, URI.create("https://bank.example.com/pay"))
                            .build(),
                        rejecting)
                    .block(Duration.ofSeconds(10)));
    assertEquals(422, e.detail().status());
  }

  @Test
  void anSslBundleMustExist() {
    AxizHttpClientProperties props =
        new Binder(
                new MapConfigurationPropertySource(
                    Map.of(
                        "axiz.http-client.clients.bank.base-url", "https://bank.example.com",
                        "axiz.http-client.clients.bank.ssl-bundle", "partner-mtls")))
            .bindOrCreate("axiz.http-client", AxizHttpClientProperties.class);
    HttpClientRegistry noBundles =
        new HttpClientRegistry(
            props,
            CircuitBreakerRegistry.ofDefaults(),
            null,
            null,
            null,
            Clock.systemUTC(),
            null,
            ObservationRegistry.NOOP,
            TransportSecurityPolicy.system());
    ClientSettings bank = noBundles.find("bank").orElseThrow();
    assertThat(assertThrows(IllegalStateException.class, () -> noBundles.transport(bank)))
        .hasMessageContaining("partner-mtls");
    HttpClientRegistry emptyBundles =
        new HttpClientRegistry(
            props,
            CircuitBreakerRegistry.ofDefaults(),
            null,
            null,
            new DefaultSslBundleRegistry(),
            Clock.systemUTC(),
            null,
            ObservationRegistry.NOOP,
            TransportSecurityPolicy.system());
    assertThrows(NoSuchSslBundleException.class, () -> emptyBundles.transport(bank));
    assertEquals(1, noBundles.all().size());
  }

  @Test
  void theClientsCanBeSwitchedOff() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizHttpClientAutoConfiguration.class))
        .withPropertyValues("axiz.http-client.enabled=false")
        .run(ctx -> assertThat(ctx).doesNotHaveBean(HttpClientRegistry.class));
    runner()
        .run(
            ctx -> {
              assertThat(ctx).hasSingleBean(AxizHttpClients.class);
              assertNull(ctx.getBean(HttpClientRegistry.class).tenantHeader());
            });
  }
}
