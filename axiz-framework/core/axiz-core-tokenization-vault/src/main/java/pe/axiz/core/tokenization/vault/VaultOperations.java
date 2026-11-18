package pe.axiz.core.tokenization.vault;

import java.util.Optional;
import pe.axiz.core.tokenization.SensitiveValue;
import pe.axiz.core.tokenization.Token;
import pe.axiz.core.tokenization.TokenDescriptor;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenType;
import pe.axiz.core.tokenization.TokenizationResult;

/** Server boundary: caller was verified by authentication, independently of the request DTO. */
public interface VaultOperations {
  TokenizationResult tokenize(
      TokenCaller caller,
      TokenType type,
      SensitiveValue value,
      TokenDomain domain,
      String operationId);

  Optional<Token> lookup(
      TokenCaller caller, TokenType type, SensitiveValue value, TokenDomain domain, String purpose);

  SensitiveValue detokenize(
      TokenCaller caller, TokenType type, Token token, TokenDomain domain, String purpose);

  Optional<TokenDescriptor> describe(
      TokenCaller caller, TokenType type, Token token, TokenDomain domain);

  boolean delete(TokenCaller caller, TokenType type, Token token, TokenDomain domain);
}
