package pe.axiz.spring.tokenization.vault;

import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import pe.axiz.core.tokenization.SensitiveValue;
import pe.axiz.core.tokenization.Token;
import pe.axiz.core.tokenization.TokenDescriptor;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenErrors;
import pe.axiz.core.tokenization.TokenType;
import pe.axiz.core.tokenization.TokenizationResult;
import pe.axiz.core.tokenization.TokenizerCapabilities;
import pe.axiz.core.tokenization.vault.AsyncVaultOperations;
import pe.axiz.core.tokenization.vault.TokenCaller;
import pe.axiz.spring.foundation.ReactiveStages;
import reactor.core.publisher.Mono;

/** In-process native consumer; explicit verified identity is obtained inside each subscription. */
public final class ReactiveCallerBoundTokenizer {
  private final AsyncVaultOperations engine;
  private final VerifiedTokenCaller callers;

  public ReactiveCallerBoundTokenizer(AsyncVaultOperations engine, VerifiedTokenCaller callers) {
    this.engine = engine;
    this.callers = callers;
  }

  public TokenizerCapabilities capabilities() {
    return TokenizerCapabilities.vault();
  }

  private <T> Mono<T> execute(Function<TokenCaller, CompletionStage<T>> operation) {
    return Mono.defer(
            () ->
                ReactiveSecurityContextHolder.getContext()
                    .mapNotNull(SecurityContext::getAuthentication)
                    .switchIfEmpty(Mono.error(TokenErrors.failure(TokenErrors.DENIED)))
                    .map(callers::resolve)
                    .flatMap(caller -> ReactiveStages.from(() -> operation.apply(caller))))
        .doOnDiscard(SensitiveValue.class, SensitiveValue::close);
  }

  public Mono<TokenizationResult> tokenize(
      TokenType type, SensitiveValue value, TokenDomain domain, String operationId) {
    return execute(caller -> engine.tokenize(caller, type, value, domain, operationId));
  }

  public Mono<Optional<Token>> lookup(
      TokenType type, SensitiveValue value, TokenDomain domain, String purpose) {
    return execute(caller -> engine.lookup(caller, type, value, domain, purpose));
  }

  public Mono<Optional<TokenDescriptor>> describe(TokenType type, Token token, TokenDomain domain) {
    return execute(caller -> engine.describe(caller, type, token, domain));
  }

  public Mono<SensitiveValue> detokenize(
      TokenType type, Token token, TokenDomain domain, String purpose) {
    return execute(caller -> engine.detokenize(caller, type, token, domain, purpose));
  }

  public Mono<Boolean> delete(TokenType type, Token token, TokenDomain domain) {
    return execute(caller -> engine.delete(caller, type, token, domain));
  }
}
