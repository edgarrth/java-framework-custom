package pe.axiz.core.tokenization;

/** Server-registered type; selecting a name never grants permission or changes policy. */
public record TokenType(String value) {
  public TokenType {
    if (value == null || !value.matches("[A-Z][A-Z0-9_-]{0,31}"))
      throw TokenErrors.failure(TokenErrors.INVALID);
  }
}
