package pe.axiz.spring.tokenization;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import pe.axiz.core.tokenization.SensitiveValue;
import pe.axiz.core.tokenization.Token;
import pe.axiz.core.tokenization.TokenDescriptor;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenOperation;
import pe.axiz.core.tokenization.TokenType;
import pe.axiz.core.tokenization.TokenizationResult;
import pe.axiz.core.tokenization.Tokenizer;
import pe.axiz.core.tokenization.TokenizerCapabilities;

/**
 * Límite remoto imperativo; las solicitudes contienen valores o tokens, nunca claims de invocante
 * de confianza.
 */
public final class RemoteTokenizer implements Tokenizer {
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Injected thread-safe HTTP client; authentication, transport and lifecycle are shared.")
  private final RestClient client;

  private final Duration timeout;

  public RemoteTokenizer(RestClient client, Duration timeout) {
    this.client = Objects.requireNonNull(client);
    this.timeout = Objects.requireNonNull(timeout);
  }

  @Override
  public TokenizerCapabilities capabilities() {
    return TokenizerCapabilities.vault();
  }

  @Override
  public TokenizationResult tokenize(
      TokenType type, SensitiveValue value, TokenDomain domain, String operationId) {
    return exchange(
        TokenValueRequestWriter.value(TokenOperation.TOKENIZE, type, value, domain, operationId),
        response -> response.tokenized(type));
  }

  @Override
  public Optional<Token> lookup(
      TokenType type, SensitiveValue value, TokenDomain domain, String purpose) {
    return exchange(
        TokenValueRequestWriter.value(TokenOperation.LOOKUP, type, value, domain, purpose),
        response -> response.lookup(type));
  }

  @Override
  public Optional<TokenDescriptor> describe(TokenType type, Token token, TokenDomain domain) {
    return exchange(
        TokenValueRequestWriter.token(TokenOperation.DESCRIBE, type, token, domain, null),
        response -> response.describe(type));
  }

  @Override
  public SensitiveValue detokenize(
      TokenType type, Token token, TokenDomain domain, String purpose) {
    return exchange(
        TokenValueRequestWriter.token(TokenOperation.DETOKENIZE, type, token, domain, purpose),
        response -> response.revealed(type));
  }

  @Override
  public boolean delete(TokenType type, Token token, TokenDomain domain) {
    return exchange(
        TokenValueRequestWriter.token(TokenOperation.DELETE, type, token, domain, null),
        TokenRemoteResponse::deleted);
  }

  private <T> T exchange(TokenValueRequestWriter request, Function<TokenRemoteResponse, T> decode) {
    long started = System.nanoTime();
    try (request) {
      return Objects.requireNonNull(
          client
              .post()
              .uri(request.path())
              .contentType(MediaType.APPLICATION_JSON)
              .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON)
              .header("Cache-Control", "no-store")
              .body(request.bytes())
              .exchange(
                  (outgoing, response) -> {
                    try (var reply =
                        new TokenRemoteResponse(
                            response.getStatusCode().value(),
                            response.getHeaders(),
                            read(response.getBody(), started),
                            request.mutates())) {
                      return decode.apply(reply);
                    }
                  }));
    } catch (RuntimeException failure) {
      throw RemoteTokenFailure.safe(failure, request.mutates());
    }
  }

  private byte[] read(InputStream input, long started) throws IOException {
    byte[] buffer = new byte[TokenRemoteResponse.MAX_BYTES + 1];
    int size = 0;
    try {
      while (true) {
        if (System.nanoTime() - started >= timeout.toNanos())
          throw new IOException("Token response deadline exceeded");
        int count = input.read(buffer, size, buffer.length - size);
        if (count < 0) return Arrays.copyOf(buffer, size);
        size += count;
        if (size > TokenRemoteResponse.MAX_BYTES)
          throw new IOException("Token response limit exceeded");
      }
    } finally {
      Arrays.fill(buffer, (byte) 0);
    }
  }
}
