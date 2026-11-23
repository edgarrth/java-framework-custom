package pe.axiz.spring.tokenization;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import pe.axiz.core.tokenization.SensitiveValue;
import pe.axiz.core.tokenization.Token;
import pe.axiz.core.tokenization.TokenDescriptor;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenOperation;
import pe.axiz.core.tokenization.TokenType;
import pe.axiz.core.tokenization.TokenizationResult;
import pe.axiz.core.tokenization.TokenizerCapabilities;
import reactor.core.publisher.Mono;

/**
 * API remota nativa; la identidad de relay se lee al suscribirse desde el contexto de Reactor de
 * ese suscriptor.
 */
public final class ReactiveRemoteTokenizer implements ReactiveTokenizer {
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Injected thread-safe HTTP client; authentication, transport and lifecycle are shared.")
  private final WebClient client;

  private final Duration timeout;

  public ReactiveRemoteTokenizer(WebClient client, Duration timeout) {
    this.client = Objects.requireNonNull(client);
    this.timeout = Objects.requireNonNull(timeout);
  }

  @Override
  public TokenizerCapabilities capabilities() {
    return TokenizerCapabilities.vault();
  }

  @Override
  public Mono<TokenizationResult> tokenize(
      TokenType type, SensitiveValue value, TokenDomain domain, String operationId) {
    return exchange(
        () ->
            TokenValueRequestWriter.value(
                TokenOperation.TOKENIZE, type, value, domain, operationId),
        response -> response.tokenized(type));
  }

  @Override
  public Mono<Optional<Token>> lookup(
      TokenType type, SensitiveValue value, TokenDomain domain, String purpose) {
    return exchange(
        () -> TokenValueRequestWriter.value(TokenOperation.LOOKUP, type, value, domain, purpose),
        response -> response.lookup(type));
  }

  @Override
  public Mono<Optional<TokenDescriptor>> describe(TokenType type, Token token, TokenDomain domain) {
    return exchange(
        () -> TokenValueRequestWriter.token(TokenOperation.DESCRIBE, type, token, domain, null),
        response -> response.describe(type));
  }

  @Override
  public Mono<SensitiveValue> detokenize(
      TokenType type, Token token, TokenDomain domain, String purpose) {
    return exchange(
        () ->
            TokenValueRequestWriter.token(TokenOperation.DETOKENIZE, type, token, domain, purpose),
        response -> response.revealed(type));
  }

  @Override
  public Mono<Boolean> delete(TokenType type, Token token, TokenDomain domain) {
    return exchange(
        () -> TokenValueRequestWriter.token(TokenOperation.DELETE, type, token, domain, null),
        TokenRemoteResponse::deleted);
  }

  private <T> Mono<T> exchange(
      Supplier<TokenValueRequestWriter> writer, Function<TokenRemoteResponse, T> decode) {
    return Mono.using(
            writer::get,
            request ->
                client
                    .post()
                    .uri(request.path())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON)
                    .header("Cache-Control", "no-store")
                    .body(
                        BodyInserters.fromDataBuffers(
                            Mono.fromSupplier(
                                () ->
                                    DefaultDataBufferFactory.sharedInstance
                                        .allocateBuffer(request.bytes().length)
                                        .write(request.bytes()))))
                    .exchangeToMono(
                        response ->
                            DataBufferUtils.join(
                                    response.body(BodyExtractors.toDataBuffers()),
                                    TokenRemoteResponse.MAX_BYTES)
                                .map(
                                    buffer -> {
                                      byte[] bytes = new byte[buffer.readableByteCount()];
                                      try {
                                        buffer.read(bytes);
                                      } finally {
                                        DataBufferUtils.release(buffer);
                                      }
                                      return bytes;
                                    })
                                .defaultIfEmpty(new byte[0])
                                .map(
                                    bytes -> {
                                      try (var reply =
                                          new TokenRemoteResponse(
                                              response.statusCode().value(),
                                              response.headers().asHttpHeaders(),
                                              bytes,
                                              request.mutates())) {
                                        return decode.apply(reply);
                                      }
                                    }))
                    .timeout(timeout)
                    .onErrorMap(failure -> RemoteTokenFailure.safe(failure, request.mutates())),
            TokenValueRequestWriter::close)
        .doOnDiscard(SensitiveValue.class, SensitiveValue::close)
        .doOnDiscard(DataBuffer.class, DataBufferUtils::release)
        .doOnDiscard(byte[].class, bytes -> Arrays.fill(bytes, (byte) 0));
  }
}
