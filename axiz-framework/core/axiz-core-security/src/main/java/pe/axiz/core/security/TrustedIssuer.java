package pe.axiz.core.security;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Emisor que el servicio acepta. La ubicación del JWKS se configura (o se descubre a partir de los
 * metadatos OpenID del emisor configurado), nunca se toma del token.
 *
 * @param issuer valor exacto de {@code iss}
 * @param jwkSetUri endpoint JWKS del emisor; null para descubrirlo desde {@code
 *     <issuer>/.well-known/openid-configuration}
 * @param audiences audiencias aceptadas (al menos una)
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

  /** Indica si las claves se descubren a partir de los metadatos del emisor. */
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
