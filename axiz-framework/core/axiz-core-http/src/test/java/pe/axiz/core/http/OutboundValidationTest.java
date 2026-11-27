package pe.axiz.core.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import pe.axiz.core.commons.net.TransportSecurityPolicy;
import pe.axiz.core.errors.ExternalServiceException;
import pe.axiz.core.resilience.TimeoutSettings;

class OutboundValidationTest {
  private static final URI ENDPOINT = URI.create("https://idp.example/token");

  @Test
  void incompleteCredentialsAndInvalidBudgetsAreRejectedBeforeTransport() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AuthSettings.builder()
                .type(AuthType.CLIENT_CREDENTIALS)
                .tokenUri(ENDPOINT)
                .clientId(null)
                .clientSecretRef("secret")
                .scopes(List.of())
                .audience(null)
                .apiKeyHeader(null)
                .apiKeyRef(null)
                .build());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AuthSettings.builder()
                .type(AuthType.CLIENT_CREDENTIALS)
                .tokenUri(ENDPOINT)
                .clientId("client")
                .clientSecretRef(null)
                .scopes(List.of())
                .audience(null)
                .apiKeyHeader(null)
                .apiKeyRef(null)
                .build());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AuthSettings.builder()
                .type(AuthType.API_KEY)
                .tokenUri(null)
                .clientId(null)
                .clientSecretRef(null)
                .scopes(List.of())
                .audience(null)
                .apiKeyHeader(null)
                .apiKeyRef("secret")
                .build());
    var auth =
        AuthSettings.builder()
            .type(AuthType.CLIENT_CREDENTIALS)
            .tokenUri(ENDPOINT)
            .clientId("client")
            .clientSecretRef("secret")
            .scopes(List.of())
            .audience(null)
            .apiKeyHeader(null)
            .apiKeyRef(null)
            .build();
    for (int attempts : List.of(0, 6))
      assertThrows(IllegalArgumentException.class, () -> settings(auth, attempts, 10));
    assertThrows(IllegalArgumentException.class, () -> settings(auth, 1, 0));
    var valid = settings(auth, 1, 10);
    assertFalse(HttpRetryRules.shouldRetry(valid, "GET", null, new RuntimeException(), 0));
    for (Duration timeout : List.of(Duration.ZERO, Duration.ofSeconds(-1))) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new AsyncHttpExchange.Request(ENDPOINT, "POST", Map.of(), "", timeout));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new ClientCredentialsExchange(
                  ENDPOINT, "client", List.of(), null, timeout, TransportSecurityPolicy.secure()));
    }
  }

  private static ClientSettings settings(AuthSettings auth, int attempts, int connections) {
    return ClientSettings.builder()
        .name("ledger")
        .baseUrl(ENDPOINT)
        .timeouts(TimeoutSettings.defaults())
        .auth(auth)
        .retry(ClientSettings.RetryMode.NONE)
        .maxAttempts(attempts)
        .circuitBreaker(null)
        .maxConcurrentCalls(0)
        .propagateHeaders(List.of())
        .maxConnections(connections)
        .transportSecurity(TransportSecurityPolicy.secure())
        .build();
  }

  @Test
  void malformedTokenClaimsNeverBecomeUsableCredentialsOrLeakTheirPayload() {
    var wire =
        new ClientCredentialsExchange(
            ENDPOINT,
            "client",
            List.of(),
            null,
            Duration.ofSeconds(1),
            TransportSecurityPolicy.secure());
    assertEquals("grant_type=client_credentials", wire.request("secret").body());
    for (String body :
        List.of(
            "{}",
            "{\"access_token\":7}",
            "{\"access_token\":\" \"}",
            "{\"access_token\":\"sensitive\",\"token_type\":7}",
            "{\"access_token\":\"sensitive\",\"token_type\":\"MAC\"}",
            "{\"access_token\":\"sensitive\",\"expires_in\":0}",
            "{\"access_token\":\"sensitive\",\"expires_in\":-1}",
            "{\"access_token\":\"sensitive\",\"expires_in\":1.5}",
            "{\"access_token\":\"sensitive\",\"expires_in\":\"60\"}",
            "{\"access_token\":\"sensitive\",\"expires_in\":9223372036854775807}")) {
      var failure =
          assertThrows(
              IllegalStateException.class,
              () ->
                  wire.read(
                      new AsyncHttpExchange.Response(200, "application/json", body),
                      Instant.EPOCH,
                      Instant.EPOCH));
      assertFalse(failure.toString().contains("sensitive"));
    }
    var valid =
        wire.read(
            new AsyncHttpExchange.Response(
                200,
                "application/json",
                "{\"access_token\":\"synthetic\",\"token_type\":\"bearer\"}"),
            Instant.EPOCH,
            Instant.EPOCH);
    assertEquals(Instant.EPOCH.plusSeconds(300), valid.expiresAt());
    var denied =
        assertThrows(
            ExternalServiceException.class,
            () ->
                wire.read(
                    new AsyncHttpExchange.Response(503, "application/json", "{}"),
                    Instant.EPOCH,
                    Instant.EPOCH));
    assertSame(denied, ClientCredentialsExchange.transport(denied));
    var cancelled = new CancellationException();
    assertSame(cancelled, ClientCredentialsExchange.transport(cancelled));
    var fatal = new AssertionError();
    assertSame(
        fatal,
        assertThrows(AssertionError.class, () -> ClientCredentialsExchange.transport(fatal)));
  }
}
