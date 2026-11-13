package pe.axiz.core.http;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.AsyncLoadingCache;
import pe.axiz.core.commons.Stages;
import pe.axiz.core.commons.net.TransportSecurityPolicy;

/**
 * Coalesced native token renewal and asynchronous secret lookup through a JDK-only transport port.
 * The application owns the supplied transport and initializes it before accepting requests.
 */
public final class ClientCredentialsAsyncTokenProvider implements AsyncAccessTokenProvider {
  private final AsyncHttpExchange http;
  private final Supplier<CompletionStage<String>> clientSecret;
  private final ClientCredentialsExchange exchange;
  private final Duration renewBefore;
  private final Clock clock;
  private final AsyncLoadingCache<AccessToken> pending;
  private volatile @Nullable AccessToken cached;

  public ClientCredentialsAsyncTokenProvider(
      AsyncHttpExchange http,
      URI tokenUri,
      String clientId,
      Supplier<CompletionStage<String>> clientSecret,
      List<String> scopes,
      @Nullable String audience,
      Duration renewBefore,
      Duration timeout,
      Clock clock,
      TransportSecurityPolicy transportSecurity) {
    this.http = Objects.requireNonNull(http);
    this.clientSecret = Objects.requireNonNull(clientSecret);
    this.exchange =
        new ClientCredentialsExchange(
            tokenUri, clientId, scopes, audience, timeout, transportSecurity);
    this.renewBefore = Objects.requireNonNull(renewBefore);
    if (renewBefore.isNegative())
      throw new IllegalArgumentException("Token renewal margin must not be negative");
    this.clock = Objects.requireNonNull(clock);
    this.pending = new AsyncLoadingCache<>(Duration.ZERO, Long.MAX_VALUE, 1, clock);
  }

  @Override
  public CompletionStage<AccessToken> token() {
    return Stages.call(
        () -> {
          AccessToken current = cached;
          if (current != null
              && Duration.between(clock.instant(), current.expiresAt()).compareTo(renewBefore)
                  > 0) {
            return CompletableFuture.completedFuture(current);
          }
          return pending.get(
              "token",
              () ->
                  Stages.map(
                      fetch(),
                      fresh -> {
                        cached = fresh;
                        return fresh;
                      }));
        });
  }

  private CompletionStage<AccessToken> fetch() {
    return Stages.compose(
        Stages.call(clientSecret),
        secret -> {
          var request = exchange.request(secret);
          var requestedAt = clock.instant();
          return Stages.map(
              Stages.recover(
                  http.exchange(request, ClientCredentialsExchange.MAX_RESPONSE_BYTES),
                  failure -> {
                    throw ClientCredentialsExchange.transport(failure);
                  }),
              response -> exchange.read(response, requestedAt, clock.instant()));
        });
  }
}
