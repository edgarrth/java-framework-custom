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
import pe.axiz.core.commons.net.TransportSecurityPolicy;
import pe.axiz.core.concurrent.AsyncLoadingCache;
import pe.axiz.core.concurrent.Stages;

/**
 * Renovación nativa coalescida de tokens y consulta asíncrona de secretos mediante un puerto de
 * transporte solo del JDK. La aplicación es dueña del transporte suministrado y lo inicializa antes
 * de aceptar peticiones.
 */
public final class ClientCredentialsAsyncTokenProvider implements AsyncAccessTokenProvider {
  private final AsyncHttpExchange http;
  private final Supplier<CompletionStage<String>> clientSecret;
  private final ClientCredentialsExchange exchange;
  private final Duration renewBefore;
  private final Clock clock;
  private final AsyncLoadingCache<AccessToken> pending;
  private volatile @Nullable AccessToken cached;

  private ClientCredentialsAsyncTokenProvider(Builder builder) {
    AsyncHttpExchange http = Objects.requireNonNull(builder.http, "http");
    URI tokenUri = Objects.requireNonNull(builder.tokenUri, "tokenUri");
    String clientId = Objects.requireNonNull(builder.clientId, "clientId");
    Supplier<CompletionStage<String>> clientSecret =
        Objects.requireNonNull(builder.clientSecret, "clientSecret");
    List<String> scopes = Objects.requireNonNull(builder.scopes, "scopes");
    @Nullable String audience = builder.audience;
    Duration renewBefore = Objects.requireNonNull(builder.renewBefore, "renewBefore");
    Duration timeout = Objects.requireNonNull(builder.timeout, "timeout");
    Clock clock = Objects.requireNonNull(builder.clock, "clock");
    TransportSecurityPolicy transportSecurity =
        Objects.requireNonNull(builder.transportSecurity, "transportSecurity");
    this.http = http;
    this.clientSecret = clientSecret;
    this.exchange =
        new ClientCredentialsExchange(
            tokenUri, clientId, scopes, audience, timeout, transportSecurity);
    this.renewBefore = renewBefore;
    if (renewBefore.isNegative())
      throw new IllegalArgumentException("Token renewal margin must not be negative");
    this.clock = clock;
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

  /** Construcción nominal; build verifica los valores obligatorios y las invariantes. */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Estado mutable de construcción; se usa un builder por operación, nunca como estado compartido.
   */
  public static final class Builder {
    private @Nullable AsyncHttpExchange http = null;
    private @Nullable URI tokenUri = null;
    private @Nullable String clientId = null;
    private @Nullable Supplier<CompletionStage<String>> clientSecret = null;
    private @Nullable List<String> scopes = List.of();
    private @Nullable String audience = null;
    private @Nullable Duration renewBefore = null;
    private @Nullable Duration timeout = null;
    private @Nullable Clock clock = null;
    private @Nullable TransportSecurityPolicy transportSecurity = null;

    private Builder() {}

    public Builder http(AsyncHttpExchange value) {
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

    public Builder clientSecret(Supplier<CompletionStage<String>> value) {
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

    public ClientCredentialsAsyncTokenProvider build() {
      return new ClientCredentialsAsyncTokenProvider(this);
    }
  }
}
