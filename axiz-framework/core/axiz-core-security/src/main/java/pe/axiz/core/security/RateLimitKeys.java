package pe.axiz.core.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Construye claves de límite de tasa a partir del principal autenticado. */
public final class RateLimitKeys {

  private RateLimitKeys() {}

  /** Clave por aplicación que llama y grupo de rutas. */
  public static String forClient(AxizPrincipal principal, String routeGroup) {
    return "rl:" + routeGroup + ":" + issuerKey(principal.issuer()) + ":" + principal.clientId();
  }

  /** Clave por usuario final (para tokens de máquina recurre al cliente). */
  public static String forSubject(AxizPrincipal principal, String routeGroup) {
    return "rl:" + routeGroup + ":" + issuerKey(principal.issuer()) + ":" + principal.name();
  }

  /**
   * Los presupuestos anónimos del gateway usan la dirección de la conexión, nunca una cabecera
   * suministrada por quien llama.
   */
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
