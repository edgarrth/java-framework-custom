package pe.axiz.spring.tokenization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import pe.axiz.core.commons.net.TransportSecurityPolicy;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.RateLimitedException;
import pe.axiz.core.http.AuthType;
import pe.axiz.core.http.ClientSettings;
import pe.axiz.core.json.JsonReader;
import pe.axiz.core.tokenization.SensitiveValue;
import pe.axiz.core.tokenization.Token;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenErrors;
import pe.axiz.core.tokenization.TokenType;
import pe.axiz.spring.httpclient.AxizHttpClientProperties;
import pe.axiz.spring.httpclient.AxizHttpClients;
import pe.axiz.spring.httpclient.AxizWebClients;
import pe.axiz.spring.httpclient.HttpClientRegistry;
import pe.axiz.spring.test.BlockHoundExtension;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(BlockHoundExtension.class)
@Timeout(45)
class RemoteTokenWireTest {
  private static final Token TOKEN = Token.fromEntropy(new byte[16]);
  private static final TokenType DNI = new TokenType("DNI");
  private static final TokenDomain DOMAIN = new TokenDomain("payments");
  private static final String PAN = "4111111111111111";
  private static final String DOCUMENT = "00123456";
  private final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();
  private final ConcurrentLinkedQueue<String> values = new ConcurrentLinkedQueue<>();
  private final ConcurrentLinkedQueue<String> identities = new ConcurrentLinkedQueue<>();
  private final AtomicInteger calls = new AtomicInteger();
  private volatile int status;
  private volatile String forcedBody = "";
  private volatile boolean deleteResult = true;
  private HttpServer server;
  private HttpClientRegistry registry;
  private AxizWebClients clients;
  private AxizHttpClients rest;
  private RemoteTokenizer imperative;
  private ReactiveTokenizer nativeClient;
  private ListAppender<ILoggingEvent> logs;

  @BeforeEach
  void start() throws Exception {
    logs = new ListAppender<>();
    logs.start();
    ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logs);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(handlers);
    server.createContext(
        "/",
        exchange -> {
          try (exchange) {
            calls.incrementAndGet();
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            identities.add(auth == null ? "missing" : auth);
            var input =
                JsonReader.parseObject(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertFalse(input.containsKey("caller"));
            assertFalse(input.containsKey("tenant"));
            assertNull(exchange.getRequestURI().getQuery());
            if (input.get("value") instanceof String value) values.add(value);
            int replyStatus = status == 0 ? 200 : status;
            String type = (String) input.get("type");
            String path = exchange.getRequestURI().getPath();
            String body =
                switch (path) {
                  case "/v1/tokens" ->
                      "{\"token\":\""
                          + TOKEN.reveal()
                          + "\",\"type\":\""
                          + type
                          + "\",\"created\":false,\"metadata\":{}}";
                  case "/v1/tokens/lookup" ->
                      "{\"token\":\"" + TOKEN.reveal() + "\",\"type\":\"" + type + "\"}";
                  case "/v1/tokens/describe" ->
                      "{\"token\":\""
                          + TOKEN.reveal()
                          + "\",\"type\":\""
                          + type
                          + "\",\"metadata\":{}}";
                  case "/v1/tokens/reveal" ->
                      "{\"type\":\""
                          + type
                          + "\",\"value\":\""
                          + ("PAN".equals(type) ? PAN : DOCUMENT)
                          + "\"}";
                  case "/v1/tokens/delete" -> "";
                  default -> throw new AssertionError("Fixed route required");
                };
            if (path.endsWith("/delete") && status == 0) replyStatus = 204;
            if (status != 0) body = forcedBody;
            var headers = exchange.getResponseHeaders();
            headers.set(
                "Content-Type",
                replyStatus >= 400 ? "application/problem+json" : "application/json");
            headers.set("Cache-Control", "no-store");
            headers.set("Retry-After", "7");
            headers.set("X-Axiz-Token-Deleted", Boolean.toString(deleteResult));
            if (replyStatus == 302) headers.set("Location", "/v1/tokens/reveal");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(replyStatus, replyStatus == 204 ? -1 : bytes.length);
            if (replyStatus != 204) exchange.getResponseBody().write(bytes);
          }
        });
    server.start();
    var auth =
        AxizHttpClientProperties.Auth.builder()
            .type(AuthType.TOKEN_RELAY)
            .tokenUri(null)
            .clientId(null)
            .clientSecretRef(null)
            .scopes(List.of())
            .audience(null)
            .apiKeyHeader(null)
            .apiKeyRef(null)
            .build();
    var client =
        AxizHttpClientProperties.Client.builder()
            .baseUrl(URI.create("http://localhost:" + server.getAddress().getPort()))
            .connectTimeout(Duration.ofSeconds(2))
            .readTimeout(Duration.ofSeconds(3))
            .totalTimeout(Duration.ofSeconds(5))
            .auth(auth)
            .retry(ClientSettings.RetryMode.NONE)
            .maxAttempts(1)
            .circuitBreaker("")
            .maxConcurrentCalls(64)
            .propagateHeaders(List.of())
            .maxConnections(16)
            .sslBundle(null)
            .revealCardData(false)
            .build();
    registry =
        HttpClientRegistry.builder()
            .properties(
                new AxizHttpClientProperties(Map.of("token-vault", client), Duration.ofSeconds(60)))
            .circuitBreakers(CircuitBreakerRegistry.ofDefaults())
            .secrets(null)
            .asyncSecrets(null)
            .sslBundles(null)
            .clock(Clock.systemUTC())
            .tenantHeader(null)
            .observations(ObservationRegistry.NOOP)
            .transportSecurity(new TransportSecurityPolicy("test", true))
            .build();
    RemoteTokenSettings.validate(
        new AxizTokenizationProperties(
            "remote", new AxizTokenizationProperties.Remote("token-vault", false)),
        registry);
    rest = new AxizHttpClients(registry, RestClient::builder, JsonMapper::new);
    clients = new AxizWebClients(registry, WebClient::builder, JsonMapper::new);
    imperative = new RemoteTokenizer(rest.restClient("token-vault"), Duration.ofSeconds(5));
    nativeClient =
        new ReactiveRemoteTokenizer(clients.exchangeClient("token-vault"), Duration.ofSeconds(5));
  }

  @AfterEach
  void close() {
    SecurityContextHolder.clearContext();
    clients.close();
    rest.close();
    registry.close();
    server.stop(0);
    handlers.close();
    ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
    String text =
        logs.list.stream()
            .map(ILoggingEvent::getFormattedMessage)
            .reduce("", (a, b) -> a + "\n" + b);
    assertThat(text).doesNotContain(PAN, DOCUMENT, "poison-secret", "Bearer identity-");
    assertTrue(
        BlockHoundExtension.detectedBlockingCalls().isEmpty(),
        () -> BlockHoundExtension.detectedBlockingCalls().toString());
  }

  private static JwtAuthenticationToken identity(String subject) {
    return new JwtAuthenticationToken(
        Jwt.withTokenValue("identity-" + subject)
            .header("alg", "RS256")
            .subject(subject)
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build(),
        List.of());
  }

  @Test
  void servletSendsRealPanAndDniWithoutChangingGenericSerializersAndPreservesDelete() {
    SecurityContextHolder.getContext().setAuthentication(identity("alice"));
    for (var type : List.of(DNI, new TokenType("PAN"))) {
      String plain = type.equals(DNI) ? DOCUMENT : PAN;
      try (var value = SensitiveValue.of(plain)) {
        assertEquals(
            TOKEN, imperative.tokenize(type, value, DOMAIN, UUID.randomUUID().toString()).token());
        assertEquals(TOKEN, imperative.lookup(type, value, DOMAIN, "payment").orElseThrow());
        assertEquals(plain, value.reveal());
      }
      try (var revealed = imperative.detokenize(type, TOKEN, DOMAIN, "payment")) {
        assertEquals(plain, revealed.reveal());
      }
      assertEquals(TOKEN, imperative.describe(type, TOKEN, DOMAIN).orElseThrow().token());
    }
    assertTrue(imperative.delete(DNI, TOKEN, DOMAIN));
    deleteResult = false;
    assertFalse(imperative.delete(DNI, TOKEN, DOMAIN));
    assertThat(values).containsExactly(DOCUMENT, DOCUMENT, PAN, PAN);
    assertThat(identities).containsOnly("Bearer identity-alice");
  }

  @Test
  void nativeWireIsNonblockingAndSubscriberIdentityDoesNotMix() {
    try (var dni = SensitiveValue.of(DOCUMENT);
        var pan = SensitiveValue.of(PAN)) {
      StepVerifier.create(
              Flux.range(0, 32)
                  .flatMap(
                      i -> {
                        var type = i % 2 == 0 ? DNI : new TokenType("PAN");
                        var value = i % 2 == 0 ? dni : pan;
                        return nativeClient
                            .tokenize(type, value, DOMAIN, UUID.randomUUID().toString())
                            .flatMap(
                                result ->
                                    nativeClient.detokenize(
                                        type, result.token(), DOMAIN, "payment"))
                            .doOnNext(
                                revealed -> {
                                  try (revealed) {
                                    assertEquals(value.reveal(), revealed.reveal());
                                  }
                                })
                            .contextWrite(
                                ReactiveSecurityContextHolder.withAuthentication(
                                    identity("caller-" + i)));
                      },
                      16)
                  .subscribeOn(Schedulers.parallel()))
          .expectNextCount(32)
          .verifyComplete();
    }
    assertEquals(64, calls.get());
    for (int i = 0; i < 32; i++) {
      String expected = "Bearer identity-caller-" + i;
      assertEquals(2, identities.stream().filter(expected::equals).count());
    }
  }

  @Test
  void uncertainAcknowledgmentIsNeverRetriedAndRateLimitKeepsItsDelay() {
    SecurityContextHolder.getContext().setAuthentication(identity("alice"));
    status = 504;
    forcedBody = "{\"code\":\"AXZ-TOKEN-0504\",\"detail\":\"poison-secret\"}";
    var error = assertThrows(BusinessException.class, () -> imperative.delete(DNI, TOKEN, DOMAIN));
    assertEquals(TokenErrors.UNCERTAIN.code(), error.code());
    assertNull(error.getCause());
    StepVerifier.create(nativeClient.delete(DNI, TOKEN, DOMAIN).subscribeOn(Schedulers.parallel()))
        .expectErrorSatisfies(
            failure ->
                assertEquals(TokenErrors.UNCERTAIN.code(), ((BusinessException) failure).code()))
        .verify();
    assertEquals(2, calls.get());
    status = 429;
    forcedBody = "{\"code\":\"AXZ-TOKEN-0429\",\"detail\":\"poison-secret\"}";
    var limited =
        assertThrows(RateLimitedException.class, () -> imperative.describe(DNI, TOKEN, DOMAIN));
    assertEquals(7, limited.retryAfterSeconds());
    StepVerifier.create(nativeClient.describe(DNI, TOKEN, DOMAIN))
        .expectErrorSatisfies(
            failure -> assertEquals(7, ((RateLimitedException) failure).retryAfterSeconds()))
        .verify();
    assertEquals(4, calls.get());
  }

  @Test
  void redirectsOversizedBodiesMalformedResponsesAndProxy404FailClosed() {
    for (int code : List.of(302, 404, 200)) {
      status = code;
      forcedBody = code == 200 ? "x".repeat(70_000) : "{\"code\":\"UNKNOWN\"}";
      assertEquals(
          TokenErrors.UNAVAILABLE.code(),
          assertThrows(BusinessException.class, () -> imperative.describe(DNI, TOKEN, DOMAIN))
              .code());
      StepVerifier.create(
              nativeClient.describe(DNI, TOKEN, DOMAIN).subscribeOn(Schedulers.parallel()))
          .expectErrorSatisfies(
              error ->
                  assertEquals(TokenErrors.UNAVAILABLE.code(), ((BusinessException) error).code()))
          .verify();
    }
    assertEquals(6, calls.get());
    status = 404;
    forcedBody = "{\"code\":\"AXZ-TOKEN-0404\"}";
    assertTrue(imperative.describe(DNI, TOKEN, DOMAIN).isEmpty());
    StepVerifier.create(nativeClient.describe(DNI, TOKEN, DOMAIN))
        .assertNext(result -> assertTrue(result.isEmpty()))
        .verifyComplete();
  }
}
