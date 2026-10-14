package pe.axiz.core.http;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.json.JsonReader;
import pe.axiz.core.errors.ExternalErrorDetail;
import pe.axiz.core.errors.ExternalServiceException;

/**
 * OAuth2 client credentials with cache and early renewal: the token is renewed when less than
 * {@code renewBefore} remains. Only one thread renews at a time; others keep using the still-valid
 * token.
 */
public final class ClientCredentialsTokenProvider implements AccessTokenProvider {

  private final HttpClient http;
  private final URI tokenUri;
  private final String clientId;
  private final Supplier<String> clientSecret;
  private final List<String> scopes;
  private final @Nullable String audience;
  private final Duration renewBefore;
  private final Duration timeout;
  private final Clock clock;
  private final ReentrantLock lock = new ReentrantLock();
  private volatile @Nullable AccessToken cached;

  public ClientCredentialsTokenProvider(
      HttpClient http,
      URI tokenUri,
      String clientId,
      Supplier<String> clientSecret,
      List<String> scopes,
      @Nullable String audience,
      Duration renewBefore,
      Duration timeout,
      Clock clock) {
    this.http = Objects.requireNonNull(http);
    this.tokenUri = Objects.requireNonNull(tokenUri);
    this.clientId = Objects.requireNonNull(clientId);
    this.clientSecret = Objects.requireNonNull(clientSecret);
    this.scopes = List.copyOf(scopes);
    this.audience = audience;
    this.renewBefore = Objects.requireNonNull(renewBefore);
    this.timeout = Objects.requireNonNull(timeout);
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public AccessToken token() {
    AccessToken current = cached;
    if (current != null && !needsRenewal(current)) {
      return current;
    }
    if (current != null && clock.instant().isBefore(current.expiresAt()) && !lock.tryLock()) {
      return current; // another thread is renewing; the current token is still valid
    }
    if (!lock.isHeldByCurrentThread()) {
      lock.lock();
    }
    try {
      AccessToken again = cached;
      if (again != null && !needsRenewal(again)) {
        return again;
      }
      AccessToken fresh = fetch();
      cached = fresh;
      return fresh;
    } finally {
      lock.unlock();
    }
  }

  private boolean needsRenewal(AccessToken token) {
    Instant now = clock.instant();
    Duration remaining = Duration.between(now, token.expiresAt());
    return remaining.compareTo(renewBefore) <= 0;
  }

  private AccessToken fetch() {
    StringBuilder form = new StringBuilder("grant_type=client_credentials");
    if (!scopes.isEmpty()) {
      form.append("&scope=").append(enc(String.join(" ", scopes)));
    }
    if (audience != null) {
      form.append("&audience=").append(enc(audience));
    }
    String basic =
        Base64.getEncoder()
            .encodeToString(
                (enc(clientId) + ":" + enc(clientSecret.get())).getBytes(StandardCharsets.UTF_8));
    HttpRequest request =
        HttpRequest.newBuilder(tokenUri)
            .timeout(timeout)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .header("Authorization", "Basic " + basic)
            .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
            .build();
    Instant requestedAt = clock.instant();
    HttpResponse<String> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new ExternalServiceException(
          pe.axiz.core.errors.CoreErrors.EXTERNAL_ERROR,
          new ExternalErrorDetail("oauth2-token", -1, null, e.getClass().getSimpleName(), ""),
          e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while obtaining access token", e);
    }
    if (response.statusCode() != 200) {
      throw ExternalErrorTranslator.translate(
          "oauth2-token",
          response.statusCode(),
          response.headers().firstValue("Content-Type").orElse(null),
          response.body());
    }
    Map<String, @Nullable Object> json = JsonReader.parseObject(response.body());
    Object token = json.get("access_token");
    if (!(token instanceof String value) || value.isBlank()) {
      throw new IllegalStateException("Token response without access_token");
    }
    long expiresIn = json.get("expires_in") instanceof BigDecimal bd ? bd.longValue() : 300L;
    // expiry is measured from the request time, so network latency never extends the token
    return new AccessToken(value, requestedAt.plus(Duration.ofSeconds(Math.max(expiresIn, 1))));
  }

  private static String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
