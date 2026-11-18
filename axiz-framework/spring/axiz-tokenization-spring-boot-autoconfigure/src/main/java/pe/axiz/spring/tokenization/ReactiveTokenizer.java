package pe.axiz.spring.tokenization;

import java.util.Optional;
import pe.axiz.core.tokenization.SensitiveValue;
import pe.axiz.core.tokenization.Token;
import pe.axiz.core.tokenization.TokenDescriptor;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenType;
import pe.axiz.core.tokenization.TokenizationResult;
import pe.axiz.core.tokenization.TokenizerCapabilities;
import reactor.core.publisher.Mono;

/** Consumer's Reactor API. Calls are executed with the identity of each subscription. */
public interface ReactiveTokenizer {
  TokenizerCapabilities capabilities();

  Mono<TokenizationResult> tokenize(
      TokenType type, SensitiveValue value, TokenDomain domain, String operationId);

  Mono<Optional<Token>> lookup(
      TokenType type, SensitiveValue value, TokenDomain domain, String purpose);

  Mono<Optional<TokenDescriptor>> describe(TokenType type, Token token, TokenDomain domain);

  Mono<SensitiveValue> detokenize(TokenType type, Token token, TokenDomain domain, String purpose);

  Mono<Boolean> delete(TokenType type, Token token, TokenDomain domain);
}
