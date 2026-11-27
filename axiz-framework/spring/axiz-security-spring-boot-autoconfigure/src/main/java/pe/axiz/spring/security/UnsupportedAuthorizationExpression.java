package pe.axiz.spring.security;

/**
 * Declaración fuera de la gramática de cobertura; nunca se evalúa durante el arranque de la
 * aplicación.
 */
final class UnsupportedAuthorizationExpression extends IllegalArgumentException {
  UnsupportedAuthorizationExpression(String message) {
    super(message);
  }
}
