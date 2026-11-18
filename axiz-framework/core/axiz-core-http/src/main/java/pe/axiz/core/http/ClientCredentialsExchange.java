package pe.axiz.core.http;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.net.TransportSecurityPolicy;
import pe.axiz.core.errors.AxizException;
import pe.axiz.core.errors.CoreErrors;
import pe.axiz.core.errors.ExternalErrorDetail;
import pe.axiz.core.errors.ExternalServiceException;
import pe.axiz.core.json.JsonReader;

/** Shared OAuth wire format and validation for synchronous and native transports. */
public final class ClientCredentialsExchange {
  public static final long MAX_RESPONSE_BYTES = 65_536;
  private final URI tokenUri;
  private final String encodedClientId;
  private final String form;
  private final Duration timeout;

  public ClientCredentialsExchange(
      URI tokenUri,
      String clientId,
      List<String> scopes,
      @Nullable String audience,
      Duration timeout,
      TransportSecurityPolicy transportSecurity) {
    this.tokenUri = Objects.requireNonNull(tokenUri);
    Objects.requireNonNull(transportSecurity).validate(tokenUri, "OAuth token-uri");
    if ((!"http".equalsIgnoreCase(tokenUri.getScheme())
            && !"https".equalsIgnoreCase(tokenUri.getScheme()))
        || tokenUri.getHost() == null
        || tokenUri.getUserInfo() != null
        || tokenUri.getFragment() != null) {
      throw new IllegalArgumentException(
          "Token endpoint must be an absolute HTTP(S) URI without userinfo or fragment");
    }
    this.encodedClientId = encode(Objects.requireNonNull(clientId));
    this.timeout = Objects.requireNonNull(timeout);
    if (!timeout.isPositive()) throw new IllegalArgumentException("Token timeout must be positive");
    var body = new StringBuilder("grant_type=client_credentials");
    List<String> immutableScopes = List.copyOf(scopes);
    if (!immutableScopes.isEmpty())
      body.append("&scope=").append(encode(String.join(" ", immutableScopes)));
    if (audience != null) body.append("&audience=").append(encode(audience));
    form = body.toString();
  }

  public AsyncHttpExchange.Request request(String clientSecret) {
    String basic =
        Base64.getEncoder()
            .encodeToString(
                (encodedClientId + ":" + encode(Objects.requireNonNull(clientSecret)))
                    .getBytes(StandardCharsets.UTF_8));
    return new AsyncHttpExchange.Request(
        tokenUri,
        "POST",
        Map.of(
            "Content-Type", List.of("application/x-www-form-urlencoded"),
            "Accept", List.of("application/json"),
            "Authorization", List.of("Basic " + basic)),
        form,
        timeout);
  }

  public AccessToken read(
      AsyncHttpExchange.Response response, Instant requestedAt, Instant receivedAt) {
    if (response.status() != 200)
      throw ExternalErrorTranslator.translate(
          "oauth2-token", response.status(), response.contentType(), response.body());
    Map<String, @Nullable Object> json;
    try {
      json = JsonReader.parseObject(response.body());
    } catch (IllegalArgumentException invalid) {
      // Parser diagnostics may include a character from a token or client secret.
      throw new IllegalStateException("Token endpoint returned invalid JSON");
    }
    Object token = json.get("access_token");
    if (!(token instanceof String value) || value.isBlank())
      throw new IllegalStateException("Token response without access_token");
    Object type = json.get("token_type");
    if (type != null && (!(type instanceof String text) || !"Bearer".equalsIgnoreCase(text)))
      throw new IllegalStateException("Token response has an unsupported token_type");
    Object expiry = json.get("expires_in");
    try {
      long seconds = expiry == null ? 300 : ((BigDecimal) expiry).longValueExact();
      if (seconds < 1) throw new ArithmeticException();
      Instant expiryAt = requestedAt.plusSeconds(seconds);
      if (!expiryAt.isAfter(receivedAt))
        throw new IllegalStateException("Access token expired before its response was received");
      return new AccessToken(value, expiryAt);
    } catch (ArithmeticException | ClassCastException | DateTimeException invalid) {
      throw new IllegalStateException("Token response has an invalid expires_in");
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  public static RuntimeException transport(Throwable failure) {
    if (failure instanceof AxizException axiz) return axiz;
    if (failure instanceof CancellationException cancelled) return cancelled;
    if (failure instanceof Error fatal) throw fatal;
    return new ExternalServiceException(
        CoreErrors.EXTERNAL_ERROR,
        new ExternalErrorDetail("oauth2-token", -1, null, failure.getClass().getSimpleName(), ""),
        failure);
  }
}
