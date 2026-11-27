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

/** Simulación local solo de CPU. Nunca se usa como respaldo de una operación remota fallida. */
final class SyntheticReactiveTokenizer implements ReactiveTokenizer {
  private final SyntheticTokenizer delegate;

  SyntheticReactiveTokenizer(SyntheticTokenizer delegate) {
    this.delegate = delegate;
  }

  @Override
  public TokenizerCapabilities capabilities() {
    return delegate.capabilities();
  }

  @Override
  public Mono<TokenizationResult> tokenize(
      TokenType type, SensitiveValue value, TokenDomain domain, String operationId) {
    return Mono.fromSupplier(() -> delegate.tokenize(type, value, domain, operationId));
  }

  @Override
  public Mono<Optional<Token>> lookup(
      TokenType type, SensitiveValue value, TokenDomain domain, String purpose) {
    return Mono.fromSupplier(() -> delegate.lookup(type, value, domain, purpose));
  }

  @Override
  public Mono<Optional<TokenDescriptor>> describe(TokenType type, Token token, TokenDomain domain) {
    return Mono.fromSupplier(() -> delegate.describe(type, token, domain));
  }

  @Override
  public Mono<SensitiveValue> detokenize(
      TokenType type, Token token, TokenDomain domain, String purpose) {
    return Mono.fromSupplier(() -> delegate.detokenize(type, token, domain, purpose))
        .doOnDiscard(SensitiveValue.class, SensitiveValue::close);
  }

  @Override
  public Mono<Boolean> delete(TokenType type, Token token, TokenDomain domain) {
    return Mono.fromSupplier(() -> delegate.delete(type, token, domain));
  }
}
