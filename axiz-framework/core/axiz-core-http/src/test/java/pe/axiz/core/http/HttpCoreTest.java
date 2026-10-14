package pe.axiz.core.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pe.axiz.core.errors.ExternalServiceException;

class HttpCoreTest {

  private HttpServer server;
  private final AtomicInteger tokenCalls = new AtomicInteger();
  private final AtomicReference<String> lastAuth = new AtomicReference<>();
  private final AtomicReference<String> lastBody = new AtomicReference<>();
  private volatile int status = 200;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/token",
        exchange -> {
          tokenCalls.incrementAndGet();
          lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
          lastBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] body =
              (status == 200
                      ? "{\"access_token\":\"t"
                          + tokenCalls.get()
                          + "\",\"expires_in\":300,\"token_type\":\"Bearer\"}"
                      : "{\"error\":\"invalid_client\",\"error_description\":\"bad secret\"}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void clientCredentialsCachesAndRenewsEarly() {
    MutableClock clock = new MutableClock();
    ClientCredentialsTokenProvider provider = provider(clock);
    AccessToken first = provider.token();
    assertEquals("t1", first.value());
    assertTrue(lastAuth.get().startsWith("Basic "));
    assertTrue(lastBody.get().contains("grant_type=client_credentials"));
    assertTrue(lastBody.get().contains("scope=payments.read+payments.write"));
    assertTrue(lastBody.get().contains("audience=core"));
    assertSame(first, provider.token());
    clock.now = clock.now.plusSeconds(250); // 50 s left < 60 s renew-before
    assertEquals("t2", provider.token().value());
    assertEquals(2, tokenCalls.get());
    assertFalse(first.toString().contains("t1"));
  }

  @Test
  void tokenEndpointErrorsAreExternalErrors() {
    status = 401;
    ExternalServiceException e =
        assertThrows(ExternalServiceException.class, () -> provider(new MutableClock()).token());
    assertEquals("invalid_client", e.detail().code());
    assertEquals("bad secret", e.detail().message());
    assertEquals(502, e.category().httpStatus());
  }

  private ClientCredentialsTokenProvider provider(Clock clock) {
    return new ClientCredentialsTokenProvider(
        HttpClient.newHttpClient(),
        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/token"),
        "my-client",
        () -> "s3cr&t",
        List.of("payments.read", "payments.write"),
        "core",
        Duration.ofSeconds(60),
        Duration.ofSeconds(2),
        clock);
  }

  @Test
  void translatesErrorShapes() {
    ExternalServiceException problem =
        ExternalErrorTranslator.translate(
            "core",
            409,
            "application/problem+json",
            "{\"title\":\"Conflict\",\"detail\":\"Duplicated\",\"code\":\"C-1\"}");
    assertEquals("C-1", problem.detail().code());
    assertEquals("Duplicated", problem.detail().message());
    assertEquals(409, problem.category().httpStatus());
    ExternalServiceException empty = ExternalErrorTranslator.translate("core", 503, null, null);
    assertTrue(empty.detail().emptyBody());
    assertEquals(503, empty.category().httpStatus());
    ExternalServiceException notJson =
        ExternalErrorTranslator.translate("core", 500, "application/json", "<html>");
    assertNull(notJson.detail().code());
    assertEquals("<html>", notJson.detail().body());
    assertEquals(
        "7",
        ExternalErrorTranslator.translate("x", 400, "application/json", "{\"code\":7}")
            .detail()
            .code());
  }

  @Test
  void headerPropagationIsAllowListedAndNeverForwardsCredentials() {
    HeaderPropagation p = new HeaderPropagation(List.of("X-Channel", "Authorization"));
    Map<String, String> inbound =
        Map.of(
            "X-Correlation-Id",
            "c-1",
            "X-Channel",
            "web",
            "Authorization",
            "Bearer x",
            "X-Other",
            "o",
            "Accept-Language",
            "es",
            "X-Bad",
            "a\nb");
    Map<String, String> out = p.outbound(inbound.keySet(), inbound::get);
    assertEquals(
        Map.of("X-Correlation-Id", "c-1", "X-Channel", "web", "Accept-Language", "es"), out);
  }

  @Test
  void settingsValidationAndRetryRules() {
    ClientSettings s = ClientSettings.of("core-ledger", URI.create("https://core.example"));
    assertTrue(HttpRetryRules.shouldRetry(s, "GET", null, new SocketTimeoutException(), 1));
    assertFalse(HttpRetryRules.shouldRetry(s, "POST", null, new SocketTimeoutException(), 1));
    assertTrue(
        HttpRetryRules.shouldRetry(s, "POST", "idem-key-123456", new SocketTimeoutException(), 1));
    assertFalse(HttpRetryRules.shouldRetry(s, "GET", null, new SocketTimeoutException(), 3));
    assertThrows(
        IllegalArgumentException.class,
        () -> ClientSettings.of("Core_Ledger", URI.create("https://x")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AuthSettings(
                AuthType.CLIENT_CREDENTIALS, null, "c", "s", List.of(), null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AuthSettings(
                AuthType.API_KEY, null, null, null, List.of(), null, "X-Api-Key", null));
    new AuthSettings(AuthType.MTLS, null, null, null, List.of(), null, null, null);
  }

  static final class MutableClock extends Clock {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
