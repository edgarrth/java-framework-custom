package pe.axiz.spring.tokenization;

import java.util.Optional;
import java.util.concurrent.CompletionStage;
import pe.axiz.core.concurrent.Stages;
import pe.axiz.core.tokenization.AsyncTokenizer;
import pe.axiz.core.tokenization.SensitiveValue;
import pe.axiz.core.tokenization.Token;
import pe.axiz.core.tokenization.TokenDescriptor;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenType;
import pe.axiz.core.tokenization.TokenizationResult;
import pe.axiz.core.tokenization.TokenizerCapabilities;
import pe.axiz.spring.foundation.ReactiveStages;

/** Future facade for client-credentials authentication, independent of thread-local identity. */
final class AsyncRemoteTokenizer implements AsyncTokenizer {
  private final ReactiveTokenizer delegate;

  AsyncRemoteTokenizer(ReactiveTokenizer delegate) {
    this.delegate = delegate;
  }

  @Override
  public TokenizerCapabilities capabilities() {
    return delegate.capabilities();
  }

  @Override
  public CompletionStage<TokenizationResult> tokenize(
      TokenType type, SensitiveValue value, TokenDomain domain, String operationId) {
    return Stages.requireValue(delegate.tokenize(type, value, domain, operationId).toFuture());
  }

  @Override
  public CompletionStage<Optional<Token>> lookup(
      TokenType type, SensitiveValue value, TokenDomain domain, String purpose) {
    return Stages.requireValue(delegate.lookup(type, value, domain, purpose).toFuture());
  }

  @Override
  public CompletionStage<Optional<TokenDescriptor>> describe(
      TokenType type, Token token, TokenDomain domain) {
    return Stages.requireValue(delegate.describe(type, token, domain).toFuture());
  }

  @Override
  public CompletionStage<SensitiveValue> detokenize(
      TokenType type, Token token, TokenDomain domain, String purpose) {
    return ReactiveStages.toOwnedFuture(
        delegate.detokenize(type, token, domain, purpose),
        SensitiveValue.class,
        SensitiveValue::close);
  }

  @Override
  public CompletionStage<Boolean> delete(TokenType type, Token token, TokenDomain domain) {
    return Stages.requireValue(delegate.delete(type, token, domain).toFuture());
  }
}
