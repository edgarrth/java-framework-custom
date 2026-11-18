package pe.axiz.adapter.http.jdk;

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
import pe.axiz.core.http.ClientCredentialsAsyncTokenProvider;

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
              ClientCredentialsTokenProvider.builder()
                  .http(http)
                  .tokenUri(endpoint)
                  .clientId("client")
                  .clientSecret(
                      () -> {
                        accesses.incrementAndGet();
                        return "secret";
                      })
                  .scopes(List.of())
                  .audience(null)
                  .renewBefore(Duration.ZERO)
                  .timeout(Duration.ofSeconds(1))
                  .clock(Clock.systemUTC())
                  .transportSecurity(policy)
                  .build());
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ClientCredentialsAsyncTokenProvider.builder()
                .http(
                    (request, limit) -> {
                      accesses.incrementAndGet();
                      return CompletableFuture.failedFuture(new AssertionError("network"));
                    })
                .tokenUri(endpoint)
                .clientId("client")
                .clientSecret(
                    () -> {
                      accesses.incrementAndGet();
                      return CompletableFuture.completedFuture("secret");
                    })
                .scopes(List.of())
                .audience(null)
                .renewBefore(Duration.ZERO)
                .timeout(Duration.ofSeconds(1))
                .clock(Clock.systemUTC())
                .transportSecurity(policy)
                .build());
    assertEquals(0, accesses.get());
  }

  @Test
  void oauthCannotUseATransportThatFollowsRedirects() {
    try (var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              ClientCredentialsTokenProvider.builder()
                  .http(http)
                  .tokenUri(URI.create("https://bank.invalid/token"))
                  .clientId("client")
                  .clientSecret(() -> "secret")
                  .scopes(List.of())
                  .audience(null)
                  .renewBefore(Duration.ZERO)
                  .timeout(Duration.ofSeconds(1))
                  .clock(Clock.systemUTC())
                  .transportSecurity(TransportSecurityPolicy.secure())
                  .build());
    }
  }
}
