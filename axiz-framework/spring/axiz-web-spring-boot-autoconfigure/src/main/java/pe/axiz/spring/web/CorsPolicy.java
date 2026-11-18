package pe.axiz.spring.web;

import java.net.URI;
import java.util.Locale;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.Environments;

/** One startup policy for the servlet and reactive CORS registrations. */
final class CorsPolicy {
  private CorsPolicy() {}

  static void validate(AxizWebProperties.Cors cors, @Nullable String environment) {
    if (!cors.enabled()) return;
    boolean local = Environments.allowsInMemoryState(environment);
    if (cors.allowCredentials()) {
      if (!cors.allowedOriginPatterns().isEmpty()) {
        throw new IllegalArgumentException(
            "Credentialed CORS requires exact origins, not patterns");
      }
      for (String origin : cors.allowedOrigins()) validateExactOrigin(origin, local);
      return;
    }
    Stream.concat(cors.allowedOrigins().stream(), cors.allowedOriginPatterns().stream())
        .forEach(
            origin -> {
              String normalized = origin.strip().toLowerCase(Locale.ROOT);
              if (!local && !normalized.equals("*") && !normalized.startsWith("https://")) {
                throw new IllegalArgumentException(
                    "CORS origins require HTTPS outside local, test or ci");
              }
            });
  }

  private static void validateExactOrigin(String origin, boolean local) {
    URI uri;
    try {
      uri = URI.create(origin);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Credentialed CORS requires an exact HTTP(S) origin", e);
    }
    String scheme = uri.getScheme();
    if (scheme == null
        || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))
        || uri.getHost() == null
        || uri.getRawUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null
        || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))
        || uri.getPort() < -1
        || uri.getPort() > 65535
        || uri.getPort() == 0
        || uri.getRawAuthority().endsWith(":")) {
      throw new IllegalArgumentException(
          "Credentialed CORS requires an exact HTTP(S) origin without credentials, path, query or"
              + " fragment");
    }
    if (!local && !scheme.equalsIgnoreCase("https")) {
      throw new IllegalArgumentException("CORS origins require HTTPS outside local, test or ci");
    }
    if (uri.getPort() == (scheme.equalsIgnoreCase("https") ? 443 : 80)) {
      throw new IllegalArgumentException(
          "CORS origins must omit the default port to match browser origins");
    }
  }
}
