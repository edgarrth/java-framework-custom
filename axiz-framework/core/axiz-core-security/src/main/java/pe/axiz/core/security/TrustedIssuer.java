package pe.axiz.core.security;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * An issuer the service accepts. The JWKS location is configured (or discovered from the configured
 * issuer's OpenID metadata), never taken from the token.
 *
 * @param issuer exact {@code iss} value
 * @param jwkSetUri JWKS endpoint of the issuer; null to discover it from {@code
 *     <issuer>/.well-known/openid-configuration}
 * @param audiences accepted audiences (at least one)
 */
public record TrustedIssuer(String issuer, @Nullable URI jwkSetUri, List<String> audiences) {

  public TrustedIssuer {
    Objects.requireNonNull(issuer, "issuer");
    audiences = List.copyOf(audiences);
    if (audiences.isEmpty()) {
      throw new IllegalArgumentException("Issuer " + issuer + " needs at least one audience");
    }
    URI keys = jwkSetUri != null ? jwkSetUri : URI.create(issuer);
    if (!"https".equals(keys.getScheme()) && !isLocal(keys)) {
      throw new IllegalArgumentException("JWKS of " + issuer + " must use https");
    }
  }

  /** Whether the keys are discovered from the issuer metadata. */
  public boolean discovered() {
    return jwkSetUri == null;
  }

  private static boolean isLocal(URI uri) {
    String host = uri.getHost();
    return host != null
        && (host.equals("localhost")
            || host.equals("127.0.0.1")
            || host.endsWith(".svc.cluster.local"));
  }
}
