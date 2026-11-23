package pe.axiz.core.tokenization.vault;

import org.jspecify.annotations.Nullable;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenOperation;
import pe.axiz.core.tokenization.TokenType;

/**
 * Restricciones de acceso adicionales propiedad del servidor; las verificaciones integradas de
 * autoridad y dominio siempre aplican.
 */
@FunctionalInterface
public interface TokenAccessPolicy {
  void check(
      TokenCaller caller,
      TokenOperation operation,
      TokenType type,
      TokenDomain domain,
      @Nullable String purpose);
}
