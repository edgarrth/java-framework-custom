package pe.axiz.core.tokenization;

/** Explicit business namespace within the verified tenant. */
public record TokenDomain(String value) {
  public TokenDomain {
    if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))
      throw TokenErrors.failure(TokenErrors.INVALID);
  }
}
