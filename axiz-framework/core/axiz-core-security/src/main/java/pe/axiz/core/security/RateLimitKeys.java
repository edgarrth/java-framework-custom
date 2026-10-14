package pe.axiz.core.security;

/** Builds rate-limit keys from the authenticated principal. */
public final class RateLimitKeys {

  private RateLimitKeys() {}

  /** Key per calling application and route group. */
  public static String forClient(AxizPrincipal principal, String routeGroup) {
    return "rl:" + routeGroup + ":" + principal.issuer().hashCode() + ":" + principal.clientId();
  }

  /** Key per end user (falls back to the client for machine tokens). */
  public static String forSubject(AxizPrincipal principal, String routeGroup) {
    return "rl:" + routeGroup + ":" + principal.issuer().hashCode() + ":" + principal.name();
  }
}
