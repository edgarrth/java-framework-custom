package pe.axiz.core.tokenization.vault;

import org.jspecify.annotations.Nullable;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenOperation;
import pe.axiz.core.tokenization.TokenType;

/**
 * Additional server-owned access restrictions; the built-in authority/domain checks always apply.
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
