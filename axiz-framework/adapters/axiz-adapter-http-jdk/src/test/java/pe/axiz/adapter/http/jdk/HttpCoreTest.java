package pe.axiz.adapter.http.jdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pe.axiz.core.commons.net.TransportSecurityPolicy;
import pe.axiz.core.errors.ExternalServiceException;
import pe.axiz.core.http.AccessToken;

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
    clock.now = clock.now.plusSeconds(250); // quedan 50 s < 60 s de renew-before
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
    return ClientCredentialsTokenProvider.builder()
        .http(HttpClient.newHttpClient())
        .tokenUri(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/token"))
        .clientId("my-client")
        .clientSecret(() -> "s3cr&t")
        .scopes(List.of("payments.read", "payments.write"))
        .audience("core")
        .renewBefore(Duration.ofSeconds(60))
        .timeout(Duration.ofSeconds(2))
        .clock(clock)
        .transportSecurity(new TransportSecurityPolicy("test", true))
        .build();
  }

  static final class MutableClock extends Clock {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
