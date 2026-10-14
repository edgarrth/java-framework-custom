package pe.axiz.core.http;

/** Outbound authentication of an HTTP client. */
public enum AuthType {
  NONE,
  /** OAuth2 client credentials, token cached and renewed before expiry. */
  CLIENT_CREDENTIALS,
  /** Forwards the inbound access token (same audience chain only). */
  TOKEN_RELAY,
  /** Static API key header resolved from Secrets Manager. */
  API_KEY,
  /** Mutual TLS with a client certificate. */
  MTLS
}
