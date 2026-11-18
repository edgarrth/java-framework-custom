package pe.axiz.core.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.axiz.core.errors.ExternalServiceException;
import pe.axiz.core.sensitive.ObjectMasker;
import pe.axiz.core.sensitive.OutputChannel;

class HttpModelTest {
  @Test
  void credentialDtosAreRedactedInAllMaskingChannels() {
    var token = new AccessToken("private-token-material", Instant.now());
    var response =
        new AsyncHttpExchange.Response(200, "application/json", "private-response-material");
    var masker = ObjectMasker.defaults();
    for (var channel : OutputChannel.values()) {
      assertFalse(
          String.valueOf(masker.describe(token, channel)).contains("private-token-material"));
      assertFalse(
          String.valueOf(masker.describe(response, channel)).contains("private-response-material"));
    }
    assertEquals("private-token-material", token.value());
    assertEquals("private-response-material", response.body());
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
            AuthSettings.builder()
                .type(AuthType.CLIENT_CREDENTIALS)
                .tokenUri(null)
                .clientId("c")
                .clientSecretRef("s")
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
                .apiKeyHeader("X-Api-Key")
                .apiKeyRef(null)
                .build());
    AuthSettings.builder()
        .type(AuthType.MTLS)
        .tokenUri(null)
        .clientId(null)
        .clientSecretRef(null)
        .scopes(List.of())
        .audience(null)
        .apiKeyHeader(null)
        .apiKeyRef(null)
        .build();
  }
}
