package pe.axiz.core.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.net.TransportSecurityPolicy;

/**
 * OAuth2 client credentials with cache and early renewal: the token is renewed when less than
 * {@code renewBefore} remains. Only one thread renews at a time; others keep using the still-valid
 * token.
 */
public final class ClientCredentialsTokenProvider implements AccessTokenProvider {

  private final HttpClient http;
  private final ClientCredentialsExchange exchange;
  private final Supplier<String> clientSecret;
  private final Duration renewBefore;
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
      Clock clock,
      TransportSecurityPolicy transportSecurity) {
    this.http = Objects.requireNonNull(http);
    if (http.followRedirects() != HttpClient.Redirect.NEVER)
      throw new IllegalArgumentException("OAuth transport must disable automatic redirects");
    this.exchange =
        new ClientCredentialsExchange(
            tokenUri, clientId, scopes, audience, timeout, transportSecurity);
    this.clientSecret = Objects.requireNonNull(clientSecret);
    this.renewBefore = Objects.requireNonNull(renewBefore);
    if (renewBefore.isNegative())
      throw new IllegalArgumentException("Token renewal margin must not be negative");
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
    Instant requestedAt = clock.instant();
    HttpResponse<String> response;
    try {
      response =
          http.send(
              exchange.request(clientSecret.get()),
              HttpResponse.BodyHandlers.limiting(
                  HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
                  ClientCredentialsExchange.MAX_RESPONSE_BYTES));
    } catch (IOException e) {
      throw ClientCredentialsExchange.transport(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while obtaining access token", e);
    }
    return exchange.read(response, requestedAt, clock.instant());
  }
}
