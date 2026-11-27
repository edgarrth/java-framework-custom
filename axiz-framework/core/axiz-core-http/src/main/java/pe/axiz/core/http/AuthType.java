package pe.axiz.core.http;

/** Autenticación saliente de un cliente HTTP. */
public enum AuthType {
  NONE,
  /** Client credentials de OAuth2, con el token en caché y renovado antes de expirar. */
  CLIENT_CREDENTIALS,
  /** Reenvía el token de acceso entrante (solo dentro de la misma cadena de audiencias). */
  TOKEN_RELAY,
  /** Cabecera de API key estática resuelta desde Secrets Manager. */
  API_KEY,
  /** TLS mutuo con un certificado de cliente. */
  MTLS
}
