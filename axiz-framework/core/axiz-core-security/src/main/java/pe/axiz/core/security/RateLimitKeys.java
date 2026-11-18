package pe.axiz.core.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Builds rate-limit keys from the authenticated principal. */
public final class RateLimitKeys {

  private RateLimitKeys() {}

  /** Key per calling application and route group. */
  public static String forClient(AxizPrincipal principal, String routeGroup) {
    return "rl:" + routeGroup + ":" + issuerKey(principal.issuer()) + ":" + principal.clientId();
  }

  /** Key per end user (falls back to the client for machine tokens). */
  public static String forSubject(AxizPrincipal principal, String routeGroup) {
    return "rl:" + routeGroup + ":" + issuerKey(principal.issuer()) + ":" + principal.name();
  }

  /** Anonymous gateway budgets use the connection address, never a caller-supplied header. */
  public static String forAnonymous(String remoteAddress, String routeGroup) {
    if (remoteAddress.isBlank() || remoteAddress.length() > 128)
      throw new IllegalArgumentException("A bounded connection address is required");
    return "rl:" + routeGroup + ":anonymous:" + issuerKey(remoteAddress);
  }

  private static String issuerKey(String issuer) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(issuer.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("Rate-limit identity algorithm unavailable", unavailable);
    }
  }
}
