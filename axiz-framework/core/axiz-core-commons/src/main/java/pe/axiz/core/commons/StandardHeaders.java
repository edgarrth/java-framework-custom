package pe.axiz.core.commons;

import java.util.Locale;
import java.util.Set;

/**
 * Names of standard headers used across HTTP and messaging. Business headers live in the catalog.
 */
public final class StandardHeaders {

  public static final String TRACEPARENT = "traceparent";
  public static final String TRACESTATE = "tracestate";
  public static final String BAGGAGE = "baggage";
  public static final String CORRELATION_ID = "X-Correlation-Id";
  public static final String REQUEST_ID = "X-Request-Id";
  public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
  public static final String ACCEPT_LANGUAGE = "Accept-Language";
  public static final String AUTHORIZATION = "Authorization";
  public static final String COOKIE = "Cookie";
  public static final String SET_COOKIE = "Set-Cookie";
  public static final String API_VERSION = "API-Version";

  /** Headers that must never be logged, audited or propagated blindly. */
  public static final Set<String> NEVER_LOG =
      Set.of("authorization", "cookie", "set-cookie", "proxy-authorization", "x-api-key");

  private StandardHeaders() {}

  public static boolean isNeverLogged(String headerName) {
    return NEVER_LOG.contains(headerName.toLowerCase(Locale.ROOT));
  }
}
