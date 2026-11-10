package pe.axiz.spring.httpclient;

import io.micrometer.observation.ObservationRegistry;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;
import org.springframework.util.ClassUtils;
import pe.axiz.core.commons.Stages;
import pe.axiz.core.http.AsyncAccessTokenProvider;
import pe.axiz.core.http.AuthSettings;
import pe.axiz.core.http.AuthType;
import pe.axiz.core.http.ClientCredentialsAsyncTokenProvider;
import pe.axiz.core.http.ClientCredentialsTokenProvider;
import pe.axiz.core.http.ClientSettings;
import pe.axiz.core.secrets.AsyncSecretResolver;
import pe.axiz.core.secrets.SecretNotFoundException;
import pe.axiz.core.secrets.SecretResolver;
import pe.axiz.core.secrets.SecretValue;
import pe.axiz.spring.foundation.ReactiveInfrastructureBootstrap;
import pe.axiz.spring.foundation.ReactiveStages;

/**
 * Adds the outbound credentials of a client: OAuth2 client credentials (cached, renewed early),
 * token relay of the inbound bearer token, or an API key from the secret store. Mutual TLS is
 * configured on the transport through an SSL bundle.
 */
public final class OutboundAuth implements AutoCloseable {

  static final boolean SECURITY =
      ClassUtils.isPresent(
          "org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken",
          null);

  private final AuthSettings settings;
  private final @Nullable ClientCredentialsTokenProvider tokens;
  private final @Nullable SecretResolver secrets;
  private final @Nullable AsyncAccessTokenProvider asyncTokens;
  private final @Nullable AsyncSecretResolver asyncSecrets;
  private final @Nullable HttpClient ownedHttp;
  private final @Nullable NativeTokenHttpTransport nativeHttp;
  private final AtomicBoolean closed = new AtomicBoolean();

  private OutboundAuth(
      AuthSettings settings,
      @Nullable ClientCredentialsTokenProvider tokens,
      @Nullable SecretResolver secrets,
      @Nullable AsyncAccessTokenProvider asyncTokens,
      @Nullable AsyncSecretResolver asyncSecrets,
      @Nullable HttpClient ownedHttp,
      @Nullable NativeTokenHttpTransport nativeHttp) {
    this.settings = settings;
    this.tokens = tokens;
    this.secrets = secrets;
    this.asyncTokens = asyncTokens;
    this.asyncSecrets = asyncSecrets;
    this.ownedHttp = ownedHttp;
    this.nativeHttp = nativeHttp;
  }

  public static OutboundAuth create(
      ClientSettings client, @Nullable SecretResolver secrets, Duration renewBefore, Clock clock) {
    AuthSettings a = client.auth();
    ClientCredentialsTokenProvider tokens = null;
    HttpClient ownedHttp = null;
    switch (a.type()) {
      case CLIENT_CREDENTIALS -> {
        SecretResolver s = requireSecrets(client, secrets);
        String ref = Objects.requireNonNull(a.clientSecretRef());
        HttpClient http =
            HttpClient.newBuilder().connectTimeout(client.timeouts().connect()).build();
        ownedHttp = http;
        try {
          tokens =
              new ClientCredentialsTokenProvider(
                  http,
                  Objects.requireNonNull(a.tokenUri()),
                  Objects.requireNonNull(a.clientId()),
                  () -> s.resolve(ref).orElseThrow(() -> new SecretNotFoundException(ref)).reveal(),
                  a.scopes(),
                  a.audience(),
                  renewBefore,
                  client.timeouts().read(),
                  clock);
        } catch (RuntimeException | Error failure) {
          try {
            http.close();
          } catch (RuntimeException | Error cleanup) {
            failure.addSuppressed(cleanup);
          }
          throw failure;
        }
      }
      case API_KEY -> requireSecrets(client, secrets);
      default -> {
        // NONE, TOKEN_RELAY and MTLS need no state
      }
    }
    return new OutboundAuth(a, tokens, secrets, null, null, ownedHttp, null);
  }

  /** Builds native credentials at startup; close this owner with its application context. */
  public static OutboundAuth createAsync(
      ClientSettings client,
      @Nullable AsyncSecretResolver secrets,
      Duration renewBefore,
      Clock clock) {
    return createAsync(client, secrets, renewBefore, clock, ObservationRegistry.NOOP);
  }

  public static OutboundAuth createAsync(
      ClientSettings client,
      @Nullable AsyncSecretResolver secrets,
      Duration renewBefore,
      Clock clock,
      ObservationRegistry observations) {
    return createAsync(client, secrets, renewBefore, clock, observations, null);
  }

  public static OutboundAuth createAsync(
      ClientSettings client,
      @Nullable AsyncSecretResolver secrets,
      Duration renewBefore,
      Clock clock,
      ObservationRegistry observations,
      @Nullable SslBundle bundle) {
    if (ClassUtils.isPresent("reactor.core.publisher.Mono", OutboundAuth.class.getClassLoader())) {
      ReactiveStages.initialize();
    }
    AuthSettings a = client.auth();
    AsyncAccessTokenProvider tokens = null;
    NativeTokenHttpTransport nativeHttp = null;
    if (a.type() == AuthType.CLIENT_CREDENTIALS || a.type() == AuthType.API_KEY) {
      if (secrets == null)
        throw new IllegalStateException(
            "Native HTTP client "
                + client.name()
                + " requires AsyncSecretResolver; configure native secrets or provide that core port");
      if (a.type() == AuthType.CLIENT_CREDENTIALS) {
        ReactiveInfrastructureBootstrap.initialize();
        var source = secrets;
        var http = new NativeTokenHttpTransport(client, observations, bundle);
        nativeHttp = http;
        try {
          tokens =
              new ClientCredentialsAsyncTokenProvider(
                  http,
                  Objects.requireNonNull(a.tokenUri()),
                  Objects.requireNonNull(a.clientId()),
                  () ->
                      Stages.map(
                          source.require(Objects.requireNonNull(a.clientSecretRef())),
                          value -> value.reveal()),
                  a.scopes(),
                  a.audience(),
                  renewBefore,
                  client.timeouts().read(),
                  clock);
        } catch (RuntimeException | Error failure) {
          try {
            http.close();
          } catch (RuntimeException | Error cleanup) {
            failure.addSuppressed(cleanup);
          }
          throw failure;
        }
      }
    }
    return new OutboundAuth(a, null, null, tokens, secrets, null, nativeHttp);
  }

  /** Native headers; never falls back to the synchronous resolver or token provider. */
  public CompletionStage<Map<String, SecretValue>> headers() {
    return Stages.call(
        () -> {
          if (closed.get()) throw new IllegalStateException("Outbound authentication is closed");
          return switch (settings.type()) {
            case CLIENT_CREDENTIALS -> {
              if (asyncTokens == null)
                throw new IllegalStateException(
                    "Reactive OAuth credentials require OutboundAuth.createAsync");
              yield Stages.map(
                  asyncTokens.token(),
                  token -> Map.of("Authorization", SecretValue.of("Bearer " + token.value())));
            }
            case API_KEY -> {
              if (asyncSecrets == null)
                throw new IllegalStateException(
                    "Reactive API key credentials require AsyncSecretResolver");
              yield Stages.map(
                  asyncSecrets.require(Objects.requireNonNull(settings.apiKeyRef())),
                  value -> Map.of(Objects.requireNonNull(settings.apiKeyHeader()), value));
            }
            default -> CompletableFuture.completedFuture(Map.of());
          };
        });
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    try {
      if (ownedHttp != null) {
        ownedHttp.shutdownNow();
        ownedHttp.close();
      }
    } finally {
      if (nativeHttp != null) nativeHttp.close();
    }
  }

  /** The secret resolver an authenticated client needs, or an error naming the client. */
  private static SecretResolver requireSecrets(
      ClientSettings client, @Nullable SecretResolver secrets) {
    if (secrets == null) {
      throw new IllegalStateException(
          "axiz.http-client.clients."
              + client.name()
              + ".auth.type="
              + client.auth().type()
              + " reads its credential from the secret store: add"
              + " axiz-secrets-spring-boot-starter");
    }
    return secrets;
  }

  /** Synchronous credentials for servlet clients; reactive clients use {@link #headers()}. */
  public void apply(BiConsumer<String, String> header) {
    if (closed.get()) throw new IllegalStateException("Outbound authentication is closed");
    switch (settings.type()) {
      case CLIENT_CREDENTIALS -> {
        if (tokens == null)
          throw new IllegalStateException("Native OAuth credentials must use headers()");
        header.accept("Authorization", "Bearer " + tokens.token().value());
      }
      case API_KEY -> {
        if (secrets == null)
          throw new IllegalStateException("Native API key credentials must use headers()");
        String ref = Objects.requireNonNull(settings.apiKeyRef());
        header.accept(
            Objects.requireNonNull(settings.apiKeyHeader()),
            secrets.resolve(ref).orElseThrow(() -> new SecretNotFoundException(ref)).reveal());
      }
      case TOKEN_RELAY -> {
        String token = SECURITY ? Relay.current() : null;
        if (token != null) {
          header.accept("Authorization", "Bearer " + token);
        }
      }
      default -> {
        // nothing to add
      }
    }
  }

  public boolean relay() {
    return settings.type() == AuthType.TOKEN_RELAY;
  }

  /** Spring Security access, isolated so the dependency stays optional. */
  static final class Relay {
    private Relay() {}

    static @Nullable String current() {
      Authentication auth = SecurityContextHolder.getContext().getAuthentication();
      return auth instanceof AbstractOAuth2TokenAuthenticationToken<?> t
          ? t.getToken().getTokenValue()
          : null;
    }
  }
}
