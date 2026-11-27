package pe.axiz.core.tokenization;

/**
 * Tipo registrado en el servidor; elegir un nombre nunca concede permisos ni cambia la política.
 */
public record TokenType(String value) {
  public TokenType {
    if (value == null || !value.matches("[A-Z][A-Z0-9_-]{0,31}"))
      throw TokenErrors.failure(TokenErrors.INVALID);
  }
}
