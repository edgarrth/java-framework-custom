package pe.axiz.core.tokenization;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Frontera del consumidor: el transporte suministra las credenciales, nunca un DTO de quien llama.
 */
public interface AsyncTokenizer {
  TokenizerCapabilities capabilities();

  CompletionStage<TokenizationResult> tokenize(
      TokenType type, SensitiveValue value, TokenDomain domain, String operationId);

  CompletionStage<Optional<Token>> lookup(
      TokenType type, SensitiveValue value, TokenDomain domain, String purpose);

  CompletionStage<SensitiveValue> detokenize(
      TokenType type, Token token, TokenDomain domain, String purpose);

  CompletionStage<Optional<TokenDescriptor>> describe(
      TokenType type, Token token, TokenDomain domain);

  CompletionStage<Boolean> delete(TokenType type, Token token, TokenDomain domain);
}
