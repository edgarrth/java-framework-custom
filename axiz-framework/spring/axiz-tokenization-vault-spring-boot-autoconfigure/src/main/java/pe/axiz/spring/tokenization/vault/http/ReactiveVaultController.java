package pe.axiz.spring.tokenization.vault.http;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import pe.axiz.core.security.AuthenticatedAccess;
import pe.axiz.core.tokenization.TokenErrors;
import pe.axiz.core.tokenization.TokenOperation;
import pe.axiz.core.tokenization.vault.AsyncVaultOperations;
import pe.axiz.spring.foundation.ReactiveStages;
import pe.axiz.spring.tokenization.vault.VerifiedTokenCaller;
import reactor.core.publisher.Mono;

/** Native reactive boundary; caller resolution and ownership are scoped to each subscription. */
@RestController
@AuthenticatedAccess
public class ReactiveVaultController {
  private final AsyncVaultOperations engine;
  private final VerifiedTokenCaller callers;
  private final VaultHttpPolicy policy;

  ReactiveVaultController(
      AsyncVaultOperations engine, VerifiedTokenCaller callers, VaultHttpPolicy policy) {
    this.engine = engine;
    this.callers = callers;
    this.policy = policy;
  }

  @PostMapping({
    "/v1/tokens",
    "/v1/tokens/lookup",
    "/v1/tokens/describe",
    "/v1/tokens/reveal",
    "/v1/tokens/delete"
  })
  public Mono<Void> execute(ServerWebExchange exchange) {
    return Mono.defer(
            () -> {
              policy.transport(
                  "https".equalsIgnoreCase(exchange.getRequest().getURI().getScheme()));
              return exchange
                  .getPrincipal()
                  .switchIfEmpty(Mono.error(TokenErrors.failure(TokenErrors.DENIED)))
                  .flatMap(
                      principal -> {
                        if (!(principal instanceof Authentication authentication))
                          return Mono.error(TokenErrors.failure(TokenErrors.DENIED));
                        var caller = callers.resolve(authentication);
                        var headers = exchange.getRequest().getHeaders();
                        VaultHttpPolicy.requestHeaders(
                            headers.getFirst(HttpHeaders.CONTENT_TYPE),
                            headers.getFirst(HttpHeaders.CONTENT_ENCODING),
                            exchange.getRequest().getURI().getRawQuery(),
                            headers.getFirst("Idempotency-Key"),
                            headers.getContentLength());
                        var operation =
                            policy.operation(
                                exchange.getRequest().getPath().pathWithinApplication().value());
                        return Mono.usingWhen(
                            read(exchange, operation),
                            request ->
                                Mono.usingWhen(
                                    ReactiveStages.from(
                                        () -> VaultHttpEndpoint.execute(engine, caller, request)),
                                    reply -> write(exchange, reply),
                                    reply -> Mono.fromRunnable(reply::close)),
                            request -> Mono.fromRunnable(request::close));
                      });
            })
        .onErrorResume(
            failure ->
                exchange.getResponse().isCommitted()
                    ? Mono.error(failure)
                    : Mono.using(
                        () -> VaultHttpReply.error(failure),
                        reply -> write(exchange, reply),
                        VaultHttpReply::close))
        .doOnDiscard(VaultValueRequest.class, VaultValueRequest::close)
        .doOnDiscard(VaultHttpReply.class, VaultHttpReply::close)
        .doOnDiscard(DataBuffer.class, DataBufferUtils::release);
  }

  private static Mono<VaultValueRequest> read(
      ServerWebExchange exchange, TokenOperation operation) {
    return DataBufferUtils.join(exchange.getRequest().getBody(), VaultHttpPolicy.MAX_BODY)
        .map(
            buffer -> {
              byte[] body = new byte[buffer.readableByteCount()];
              try {
                buffer.read(body);
              } finally {
                DataBufferUtils.release(buffer);
              }
              return VaultValueRequest.read(operation, body);
            })
        .switchIfEmpty(Mono.error(TokenErrors.failure(TokenErrors.INVALID)))
        .onErrorMap(
            DataBufferLimitException.class, failure -> TokenErrors.failure(TokenErrors.INVALID));
  }

  static Mono<Void> write(ServerWebExchange exchange, VaultHttpReply reply) {
    return Mono.defer(
        () -> {
          var response = exchange.getResponse();
          response.setStatusCode(HttpStatusCode.valueOf(reply.status()));
          VaultHttpPolicy.noStore(response.getHeaders());
          if (reply.status() == 204)
            response.getHeaders().set("X-Axiz-Token-Deleted", Boolean.toString(reply.deleted()));
          if (reply.retryAfterSeconds() > 0)
            response.getHeaders().set("Retry-After", Long.toString(reply.retryAfterSeconds()));
          if (reply.status() == 204) return response.setComplete();
          response.getHeaders().setContentType(MediaType.parseMediaType(reply.contentType()));
          response.getHeaders().setContentLength(reply.body().length);
          return response.writeWith(
              Mono.fromSupplier(
                  () ->
                      response
                          .bufferFactory()
                          .allocateBuffer(reply.body().length)
                          .write(reply.body())));
        });
  }
}
