package pe.axiz.spring.tokenization.vault;

import java.util.Optional;
import org.springframework.security.core.context.SecurityContextHolder;
import pe.axiz.core.tokenization.SensitiveValue;
import pe.axiz.core.tokenization.Token;
import pe.axiz.core.tokenization.TokenDescriptor;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenErrors;
import pe.axiz.core.tokenization.TokenType;
import pe.axiz.core.tokenization.TokenizationResult;
import pe.axiz.core.tokenization.Tokenizer;
import pe.axiz.core.tokenization.TokenizerCapabilities;
import pe.axiz.core.tokenization.vault.TokenCaller;
import pe.axiz.core.tokenization.vault.VaultOperations;

/** In-process imperative consumer; resolves verified identity anew on every invocation. */
public final class CallerBoundTokenizer implements Tokenizer {
  private final VaultOperations engine;
  private final VerifiedTokenCaller callers;

  public CallerBoundTokenizer(VaultOperations engine, VerifiedTokenCaller callers) {
    this.engine = engine;
    this.callers = callers;
  }

  private TokenCaller caller() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null) throw TokenErrors.failure(TokenErrors.DENIED);
    return callers.resolve(authentication);
  }

  @Override
  public TokenizerCapabilities capabilities() {
    return TokenizerCapabilities.vault();
  }

  @Override
  public TokenizationResult tokenize(
      TokenType type, SensitiveValue value, TokenDomain domain, String operationId) {
    return engine.tokenize(caller(), type, value, domain, operationId);
  }

  @Override
  public Optional<Token> lookup(
      TokenType type, SensitiveValue value, TokenDomain domain, String purpose) {
    return engine.lookup(caller(), type, value, domain, purpose);
  }

  @Override
  public Optional<TokenDescriptor> describe(TokenType type, Token token, TokenDomain domain) {
    return engine.describe(caller(), type, token, domain);
  }

  @Override
  public SensitiveValue detokenize(
      TokenType type, Token token, TokenDomain domain, String purpose) {
    return engine.detokenize(caller(), type, token, domain, purpose);
  }

  @Override
  public boolean delete(TokenType type, Token token, TokenDomain domain) {
    return engine.delete(caller(), type, token, domain);
  }
}
