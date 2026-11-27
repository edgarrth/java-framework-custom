package pe.axiz.core.tokenization.vault;

import java.util.Optional;
import java.util.concurrent.CompletionStage;
import pe.axiz.core.tokenization.SensitiveValue;
import pe.axiz.core.tokenization.Token;
import pe.axiz.core.tokenization.TokenDescriptor;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenType;
import pe.axiz.core.tokenization.TokenizationResult;

/**
 * Frontera del servidor: la autenticación verificó a quien llama, con independencia del DTO de la
 * petición.
 */
public interface AsyncVaultOperations {
  CompletionStage<TokenizationResult> tokenize(
      TokenCaller caller,
      TokenType type,
      SensitiveValue value,
      TokenDomain domain,
      String operationId);

  CompletionStage<Optional<Token>> lookup(
      TokenCaller caller, TokenType type, SensitiveValue value, TokenDomain domain, String purpose);

  CompletionStage<SensitiveValue> detokenize(
      TokenCaller caller, TokenType type, Token token, TokenDomain domain, String purpose);

  CompletionStage<Optional<TokenDescriptor>> describe(
      TokenCaller caller, TokenType type, Token token, TokenDomain domain);

  CompletionStage<Boolean> delete(
      TokenCaller caller, TokenType type, Token token, TokenDomain domain);
}
