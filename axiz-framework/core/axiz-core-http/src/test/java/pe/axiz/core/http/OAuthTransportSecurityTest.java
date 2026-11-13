package pe.axiz.core.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import pe.axiz.core.commons.net.TransportSecurityPolicy;

class OAuthTransportSecurityTest {
  @Test
  void syncAndAsyncRejectPlaintextBeforeReadingAnySecretOrSendingAnything() {
    var accesses = new AtomicInteger();
    var endpoint = URI.create("http://localhost/token");
    var policy = TransportSecurityPolicy.secure();
    try (var http = HttpClient.newHttpClient()) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new ClientCredentialsTokenProvider(
                  http,
                  endpoint,
                  "client",
                  () -> {
                    accesses.incrementAndGet();
                    return "secret";
                  },
                  List.of(),
                  null,
                  Duration.ZERO,
                  Duration.ofSeconds(1),
                  Clock.systemUTC(),
                  policy));
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ClientCredentialsAsyncTokenProvider(
                (request, limit) -> {
                  accesses.incrementAndGet();
                  return CompletableFuture.failedFuture(new AssertionError("network"));
                },
                endpoint,
                "client",
                () -> {
                  accesses.incrementAndGet();
                  return CompletableFuture.completedFuture("secret");
                },
                List.of(),
                null,
                Duration.ZERO,
                Duration.ofSeconds(1),
                Clock.systemUTC(),
                policy));
    assertEquals(0, accesses.get());
  }

  @Test
  void oauthCannotUseATransportThatFollowsRedirects() {
    try (var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new ClientCredentialsTokenProvider(
                  http,
                  URI.create("https://bank.invalid/token"),
                  "client",
                  () -> "secret",
                  List.of(),
                  null,
                  Duration.ZERO,
                  Duration.ofSeconds(1),
                  Clock.systemUTC(),
                  TransportSecurityPolicy.secure()));
    }
  }
}
