package pe.axiz.core.tokenization;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Consumer boundary: credentials are supplied by transport, never a caller DTO. */
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
