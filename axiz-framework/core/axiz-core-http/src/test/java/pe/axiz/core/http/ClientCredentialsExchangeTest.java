package pe.axiz.core.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import pe.axiz.core.commons.net.TransportSecurityPolicy;

class ClientCredentialsExchangeTest {
  @Test
  void requestIsImmutableAndDoesNotExposeCredentialsInDiagnostics() {
    var headers = new LinkedHashMap<String, List<String>>();
    var values = new ArrayList<>(List.of("secret"));
    headers.put("Authorization", values);
    var request =
        new AsyncHttpExchange.Request(
            URI.create("https://example.test/?secret"),
            "POST",
            headers,
            "secret",
            Duration.ofSeconds(1));
    headers.clear();
    values.clear();
    assertEquals(List.of("secret"), request.headers().get("Authorization"));
    assertThrows(UnsupportedOperationException.class, () -> request.headers().clear());
    assertThrows(
        UnsupportedOperationException.class, () -> request.headers().get("Authorization").clear());
    assertFalse(request.toString().contains("secret"));
  }

  @Test
  void sharedWireProtocolAnchorsExpiryBeforeIoAndSanitizesFailures() {
    var exchange =
        new ClientCredentialsExchange(
            URI.create("https://example.test/token"),
            "client",
            List.of("read", "write"),
            "urn:bank",
            Duration.ofSeconds(2),
            TransportSecurityPolicy.secure());
    var request = exchange.request("credential");
    assertEquals("POST", request.method());
    assertEquals(
        "grant_type=client_credentials&scope=read+write&audience=urn%3Abank", request.body());
    var token =
        exchange.read(
            new AsyncHttpExchange.Response(
                200, "application/json", "{\"access_token\":\"private\",\"expires_in\":10}"),
            Instant.EPOCH,
            Instant.EPOCH.plusSeconds(2));
    assertEquals(Instant.EPOCH.plusSeconds(10), token.expiresAt());
    var invalid =
        assertThrows(
            IllegalStateException.class,
            () ->
                exchange.read(
                    new AsyncHttpExchange.Response(200, null, "private invalid body"),
                    Instant.EPOCH,
                    Instant.EPOCH));
    assertFalse(invalid.toString().contains("private"));
    assertThrows(
        IllegalStateException.class,
        () ->
            exchange.read(
                new AsyncHttpExchange.Response(
                    200, null, "{\"access_token\":\"private\",\"expires_in\":1}"),
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(1)));
  }
}
