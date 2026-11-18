package pe.axiz.adapter.http.jdk;

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
import pe.axiz.core.http.AccessToken;
import pe.axiz.core.http.AccessTokenProvider;
import pe.axiz.core.http.AsyncHttpExchange;
import pe.axiz.core.http.ClientCredentialsExchange;

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

  private ClientCredentialsTokenProvider(Builder builder) {
    HttpClient http = Objects.requireNonNull(builder.http, "http");
    URI tokenUri = Objects.requireNonNull(builder.tokenUri, "tokenUri");
    String clientId = Objects.requireNonNull(builder.clientId, "clientId");
    Supplier<String> clientSecret = Objects.requireNonNull(builder.clientSecret, "clientSecret");
    List<String> scopes = Objects.requireNonNull(builder.scopes, "scopes");
    @Nullable String audience = builder.audience;
    Duration renewBefore = Objects.requireNonNull(builder.renewBefore, "renewBefore");
    Duration timeout = Objects.requireNonNull(builder.timeout, "timeout");
    Clock clock = Objects.requireNonNull(builder.clock, "clock");
    TransportSecurityPolicy transportSecurity =
        Objects.requireNonNull(builder.transportSecurity, "transportSecurity");
    this.http = http;
    if (http.followRedirects() != HttpClient.Redirect.NEVER)
      throw new IllegalArgumentException("OAuth transport must disable automatic redirects");
    this.exchange =
        new ClientCredentialsExchange(
            tokenUri, clientId, scopes, audience, timeout, transportSecurity);
    this.clientSecret = clientSecret;
    this.renewBefore = renewBefore;
    if (renewBefore.isNegative())
      throw new IllegalArgumentException("Token renewal margin must not be negative");
    this.clock = clock;
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
              JdkRequests.request(exchange.request(clientSecret.get())),
              HttpResponse.BodyHandlers.limiting(
                  HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
                  ClientCredentialsExchange.MAX_RESPONSE_BYTES));
    } catch (IOException e) {
      throw ClientCredentialsExchange.transport(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while obtaining access token", e);
    }
    return exchange.read(
        new AsyncHttpExchange.Response(
            response.statusCode(),
            response.headers().firstValue("Content-Type").orElse(null),
            response.body()),
        requestedAt,
        clock.instant());
  }

  /** Named construction; required values and invariants are checked by build. */
  public static Builder builder() {
    return new Builder();
  }

  /** Mutable construction state; use one builder per operation, never as shared state. */
  public static final class Builder {
    private @Nullable HttpClient http = null;
    private @Nullable URI tokenUri = null;
    private @Nullable String clientId = null;
    private @Nullable Supplier<String> clientSecret = null;
    private @Nullable List<String> scopes = List.of();
    private @Nullable String audience = null;
    private @Nullable Duration renewBefore = null;
    private @Nullable Duration timeout = null;
    private @Nullable Clock clock = null;
    private @Nullable TransportSecurityPolicy transportSecurity = null;

    private Builder() {}

    public Builder http(HttpClient value) {
      this.http = value;
      return this;
    }

    public Builder tokenUri(URI value) {
      this.tokenUri = value;
      return this;
    }

    public Builder clientId(String value) {
      this.clientId = value;
      return this;
    }

    public Builder clientSecret(Supplier<String> value) {
      this.clientSecret = value;
      return this;
    }

    public Builder scopes(List<String> value) {
      this.scopes = List.copyOf(value);
      return this;
    }

    public Builder audience(@Nullable String value) {
      this.audience = value;
      return this;
    }

    public Builder renewBefore(Duration value) {
      this.renewBefore = value;
      return this;
    }

    public Builder timeout(Duration value) {
      this.timeout = value;
      return this;
    }

    public Builder clock(Clock value) {
      this.clock = value;
      return this;
    }

    public Builder transportSecurity(TransportSecurityPolicy value) {
      this.transportSecurity = value;
      return this;
    }

    public ClientCredentialsTokenProvider build() {
      return new ClientCredentialsTokenProvider(this);
    }
  }
}
