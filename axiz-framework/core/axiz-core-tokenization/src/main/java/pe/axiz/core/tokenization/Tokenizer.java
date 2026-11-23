package pe.axiz.core.tokenization;

import java.util.Optional;

/**
 * Frontera del consumidor: el transporte suministra las credenciales, nunca un DTO de quien llama.
 */
public interface Tokenizer {
  TokenizerCapabilities capabilities();

  TokenizationResult tokenize(
      TokenType type, SensitiveValue value, TokenDomain domain, String operationId);

  Optional<Token> lookup(TokenType type, SensitiveValue value, TokenDomain domain, String purpose);

  SensitiveValue detokenize(TokenType type, Token token, TokenDomain domain, String purpose);

  Optional<TokenDescriptor> describe(TokenType type, Token token, TokenDomain domain);

  boolean delete(TokenType type, Token token, TokenDomain domain);
}
