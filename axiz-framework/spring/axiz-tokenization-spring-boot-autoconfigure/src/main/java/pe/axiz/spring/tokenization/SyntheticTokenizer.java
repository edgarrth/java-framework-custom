package pe.axiz.spring.tokenization;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import pe.axiz.core.tokenization.SensitiveValue;
import pe.axiz.core.tokenization.Token;
import pe.axiz.core.tokenization.TokenDescriptor;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenErrors;
import pe.axiz.core.tokenization.TokenType;
import pe.axiz.core.tokenization.TokenizationResult;
import pe.axiz.core.tokenization.Tokenizer;
import pe.axiz.core.tokenization.TokenizerCapabilities;

/**
 * Doble de prueba acotado en memoria que solo acepta etiquetas sintéticas explícitas, nunca valores
 * reales de campos.
 */
final class SyntheticTokenizer implements Tokenizer, AutoCloseable {
  private final Map<Key, Token> entries = new HashMap<>();
  private long sequence;
  private boolean closed;

  private record Key(TokenType type, TokenDomain domain, String synthetic) {}

  @Override
  public TokenizerCapabilities capabilities() {
    return TokenizerCapabilities.vault();
  }

  private void open() {
    if (closed) throw TokenErrors.failure(TokenErrors.UNAVAILABLE);
  }

  private static Key key(TokenType type, SensitiveValue value, TokenDomain domain) {
    String synthetic = value.reveal();
    if (!synthetic.matches("synthetic:[a-zA-Z][a-zA-Z0-9_-]{0,47}"))
      throw TokenErrors.failure(TokenErrors.INVALID);
    return new Key(type, domain, synthetic);
  }

  @Override
  public synchronized TokenizationResult tokenize(
      TokenType type, SensitiveValue value, TokenDomain domain, String operationId) {
    open();
    try {
      if (!UUID.fromString(operationId).toString().equals(operationId))
        throw TokenErrors.failure(TokenErrors.INVALID);
    } catch (IllegalArgumentException invalid) {
      throw TokenErrors.failure(TokenErrors.INVALID);
    }
    var key = key(type, value, domain);
    var existing = entries.get(key);
    if (existing != null) return new TokenizationResult(existing, type, false, Map.of());
    if (entries.size() >= 1024) throw TokenErrors.limited(Duration.ofSeconds(1));
    // Deliberadamente predecible: este doble no pretende seguridad criptográfica ni persistencia.
    var token = Token.fromEntropy(ByteBuffer.allocate(16).putLong(0).putLong(++sequence).array());
    entries.put(key, token);
    return new TokenizationResult(token, type, true, Map.of());
  }

  @Override
  public synchronized Optional<Token> lookup(
      TokenType type, SensitiveValue value, TokenDomain domain, String purpose) {
    open();
    purpose(purpose);
    return Optional.ofNullable(entries.get(key(type, value, domain)));
  }

  @Override
  public synchronized Optional<TokenDescriptor> describe(
      TokenType type, Token token, TokenDomain domain) {
    open();
    return find(type, token, domain).map(key -> new TokenDescriptor(token, type, Map.of()));
  }

  @Override
  public synchronized SensitiveValue detokenize(
      TokenType type, Token token, TokenDomain domain, String purpose) {
    open();
    purpose(purpose);
    return SensitiveValue.of(
        find(type, token, domain)
            .orElseThrow(() -> TokenErrors.failure(TokenErrors.NOT_FOUND))
            .synthetic());
  }

  @Override
  public synchronized boolean delete(TokenType type, Token token, TokenDomain domain) {
    open();
    return find(type, token, domain).map(key -> entries.remove(key) != null).orElse(false);
  }

  private Optional<Key> find(TokenType type, Token token, TokenDomain domain) {
    return entries.entrySet().stream()
        .filter(
            entry ->
                entry.getKey().type().equals(type)
                    && entry.getKey().domain().equals(domain)
                    && entry.getValue().equals(token))
        .map(Map.Entry::getKey)
        .findFirst();
  }

  private static void purpose(String value) {
    if (!value.matches("[a-zA-Z0-9][a-zA-Z0-9._:-]{0,63}"))
      throw TokenErrors.failure(TokenErrors.INVALID);
  }

  @Override
  public synchronized void close() {
    closed = true;
    entries.clear();
  }
}
