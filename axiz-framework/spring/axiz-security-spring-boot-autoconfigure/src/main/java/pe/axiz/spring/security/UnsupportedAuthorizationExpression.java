package pe.axiz.spring.security;

/** A declaration outside the coverage grammar; never evaluated during application startup. */
final class UnsupportedAuthorizationExpression extends IllegalArgumentException {
  UnsupportedAuthorizationExpression(String message) {
    super(message);
  }
}
