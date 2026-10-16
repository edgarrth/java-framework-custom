package pe.axiz.spring.httpclient;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.function.BiConsumer;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;
import org.springframework.util.ClassUtils;
import pe.axiz.core.http.AuthSettings;
import pe.axiz.core.http.ClientCredentialsTokenProvider;
import pe.axiz.core.http.ClientSettings;
import pe.axiz.core.secrets.SecretNotFoundException;
import pe.axiz.core.secrets.SecretResolver;

/**
 * Adds the outbound credentials of a client: OAuth2 client credentials (cached, renewed early),
 * token relay of the inbound bearer token, or an API key from the secret store. Mutual TLS is
 * configured on the transport through an SSL bundle.
 */
public final class OutboundAuth {

  static final boolean SECURITY =
      ClassUtils.isPresent(
          "org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken",
          null);

  private final AuthSettings settings;
  private final @Nullable ClientCredentialsTokenProvider tokens;
  private final @Nullable SecretResolver secrets;

  private OutboundAuth(
      AuthSettings settings,
      @Nullable ClientCredentialsTokenProvider tokens,
      @Nullable SecretResolver secrets) {
    this.settings = settings;
    this.tokens = tokens;
    this.secrets = secrets;
  }

  public static OutboundAuth create(
      ClientSettings client, @Nullable SecretResolver secrets, Duration renewBefore, Clock clock) {
    AuthSettings a = client.auth();
    ClientCredentialsTokenProvider tokens = null;
    switch (a.type()) {
      case CLIENT_CREDENTIALS -> {
        SecretResolver s =
            Objects.requireNonNull(secrets, "client-credentials needs the secrets starter");
        String ref = Objects.requireNonNull(a.clientSecretRef());
        HttpClient http =
            HttpClient.newBuilder().connectTimeout(client.timeouts().connect()).build();
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
      }
      case API_KEY -> Objects.requireNonNull(secrets, "api-key needs the secrets starter");
      default -> {
        // NONE, TOKEN_RELAY and MTLS need no state
      }
    }
    return new OutboundAuth(a, tokens, secrets);
  }

  /**
   * Applies the credentials; blocking (call from a worker thread in reactive code). Token relay
   * reads the servlet security context here; reactive clients add the relayed token themselves
   * ({@link AxizExchangeFilter}), because it lives in the Reactor context.
   */
  public void apply(BiConsumer<String, String> header) {
    switch (settings.type()) {
      case CLIENT_CREDENTIALS ->
          header.accept(
              "Authorization", "Bearer " + Objects.requireNonNull(tokens).token().value());
      case API_KEY -> {
        String ref = Objects.requireNonNull(settings.apiKeyRef());
        header.accept(
            Objects.requireNonNull(settings.apiKeyHeader()),
            Objects.requireNonNull(secrets)
                .resolve(ref)
                .orElseThrow(() -> new SecretNotFoundException(ref))
                .reveal());
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
    return settings.type() == pe.axiz.core.http.AuthType.TOKEN_RELAY;
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
