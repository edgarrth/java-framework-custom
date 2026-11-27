package pe.axiz.core.tokenization.pan;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.Optional;
import pe.axiz.core.carddata.Pan;
import pe.axiz.core.tokenization.SensitiveValue;
import pe.axiz.core.tokenization.Token;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenType;
import pe.axiz.core.tokenization.TokenizationResult;
import pe.axiz.core.tokenization.Tokenizer;

/**
 * Fachada tipada para consumidores; el tokenizador genérico conserva todas las obligaciones de
 * autorización y auditoría.
 */
public final class PanTokenizer {
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Tokenizer is a borrowed service collaborator; its lifecycle and authorization state must be shared.")
  private final Tokenizer delegate;

  private final TokenType type;

  public PanTokenizer(Tokenizer delegate, TokenType type) {
    this.delegate = Objects.requireNonNull(delegate);
    this.type = Objects.requireNonNull(type);
  }

  public TokenizationResult tokenize(Pan pan, TokenDomain domain, String operationId) {
    try (SensitiveValue value = SensitiveValue.of(pan.reveal())) {
      return delegate.tokenize(type, value, domain, operationId);
    }
  }

  public Optional<Token> lookup(Pan pan, TokenDomain domain, String purpose) {
    try (SensitiveValue value = SensitiveValue.of(pan.reveal())) {
      return delegate.lookup(type, value, domain, purpose);
    }
  }

  public Pan detokenize(Token token, TokenDomain domain, String purpose) {
    try (SensitiveValue value = delegate.detokenize(type, token, domain, purpose)) {
      return Pan.parse(value.reveal());
    }
  }
}
