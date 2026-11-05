package pe.axiz.spring.idempotency;

import java.util.Objects;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import pe.axiz.core.commons.StandardHeaders;
import pe.axiz.core.idempotency.AsyncIdempotencyService;
import pe.axiz.core.idempotency.IdempotencyService;
import pe.axiz.core.idempotency.StoredResponse;
import pe.axiz.spring.foundation.ReactiveStages;
import pe.axiz.spring.idempotency.ReactiveIdempotency.IdempotencyExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

/**
 * Reactive counterpart of {@link IdempotencyFilter}: for unsafe requests it fingerprints the
 * buffered body (method, path, query string as sent and body) and hands it to the advice through
 * the Reactor context; the advice decides after method authorization. When the handler proceeded,
 * the captured response (up to {@code maxResponseSize}) is stored; a larger body still streams to
 * the client and only its status and headers are stored.
 */
public class IdempotencyWebFilter implements WebFilter, Ordered {

  /** After Spring Security's {@code WebFilterChainProxy} (order -100). */
  public static final int ORDER = Ordered.LOWEST_PRECEDENCE - 100;

  private static final Logger LOG = LoggerFactory.getLogger(IdempotencyWebFilter.class);

  private final AsyncIdempotencyService service;
  private final int maxBodySize;
  private final int maxResponseSize;

  public IdempotencyWebFilter(
      AsyncIdempotencyService service, int maxBodySize, int maxResponseSize) {
    this.service = Objects.requireNonNull(service);
    this.maxBodySize = maxBodySize;
    this.maxResponseSize = maxResponseSize;
  }

  @Override
  public int getOrder() {
    return ORDER;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String method = exchange.getRequest().getMethod().name();
    if (!IdempotencySupport.unsafe(method)) {
      return chain.filter(exchange);
    }
    String key = exchange.getRequest().getHeaders().getFirst(StandardHeaders.IDEMPOTENCY_KEY);
    if (key == null) {
      IdempotencyExchange state = new IdempotencyExchange(null, null);
      return chain
          .filter(exchange)
          .contextWrite(c -> c.put(ReactiveIdempotency.CONTEXT_KEY, state));
    }
    return DataBufferUtils.join(exchange.getRequest().getBody(), maxBodySize)
        .map(
            buffer -> {
              byte[] bytes = new byte[buffer.readableByteCount()];
              buffer.read(bytes);
              DataBufferUtils.release(buffer);
              return bytes;
            })
        .defaultIfEmpty(new byte[0])
        .flatMap(
            body -> {
              String fingerprint =
                  IdempotencyService.fingerprint(
                      method,
                      exchange.getRequest().getPath().value(),
                      exchange.getRequest().getURI().getRawQuery(),
                      body);
              return proceed(exchange, chain, new IdempotencyExchange(key, fingerprint), body);
            });
  }

  private Mono<Void> proceed(
      ServerWebExchange exchange, WebFilterChain chain, IdempotencyExchange state, byte[] body) {
    BoundedCapture captured = new BoundedCapture(maxResponseSize);
    ServerHttpRequestDecorator request =
        new ServerHttpRequestDecorator(exchange.getRequest()) {
          @Override
          public Flux<DataBuffer> getBody() {
            return Flux.defer(
                () -> Flux.just(exchange.getResponse().bufferFactory().wrap(body.clone())));
          }
        };
    ServerHttpResponseDecorator response =
        new ServerHttpResponseDecorator(exchange.getResponse()) {
          @Override
          public Mono<Void> writeWith(Publisher<? extends DataBuffer> data) {
            return super.writeWith(
                    Flux.from(data)
                        .map(
                            b -> {
                              byte[] chunk = new byte[b.readableByteCount()];
                              b.read(chunk);
                              DataBufferUtils.release(b);
                              captured.write(chunk);
                              return exchange.getResponse().bufferFactory().wrap(chunk);
                            }))
                .doFinally(
                    signal -> {
                      if (signal != SignalType.ON_COMPLETE) captured.markOverflowed();
                    });
          }

          @Override
          public Mono<Void> writeAndFlushWith(
              Publisher<? extends Publisher<? extends DataBuffer>> body) {
            // streamed (flushed) responses are stored without body: replaying a stream is not
            // supported, its status and headers are
            captured.markOverflowed();
            return super.writeAndFlushWith(body)
                .doFinally(
                    signal -> {
                      if (signal != SignalType.ON_COMPLETE) captured.markOverflowed();
                    });
          }

          @Override
          public Mono<Void> setComplete() {
            return super.setComplete()
                .doFinally(
                    signal -> {
                      if (signal != SignalType.ON_COMPLETE) captured.markOverflowed();
                    });
          }
        };
    ServerWebExchange mutated = exchange.mutate().request(request).response(response).build();
    return Mono.usingWhen(
        Mono.just(state),
        resource ->
            chain
                .filter(mutated)
                .contextWrite(c -> c.put(ReactiveIdempotency.CONTEXT_KEY, resource)),
        resource -> finish(resource, response, captured),
        (resource, error) -> release(resource),
        resource -> release(resource));
  }

  private Mono<Void> finish(
      IdempotencyExchange state, ServerHttpResponseDecorator response, BoundedCapture captured) {
    IdempotencyService.Proceed execution = state.execution();
    if (execution == null) {
      return Mono.empty(); // not idempotent, rejected before deciding, or a replay
    }
    state.closeLease();
    if (state.noEffect)
      return ReactiveStages.from(() -> service.releaseWithoutEffect(execution)).then();
    HttpStatusCode status = response.getStatusCode();
    int code = status != null ? status.value() : HttpStatus.OK.value();
    if (code >= 500) {
      return release(state);
    }
    StoredResponse stored =
        captured.overflowed()
            ? StoredResponse.withoutBody(
                code, IdempotencySupport.replayedHeaders(response.getHeaders()::getFirst))
            : new StoredResponse(
                code,
                IdempotencySupport.replayedHeaders(response.getHeaders()::getFirst),
                captured.bytes());
    return ReactiveStages.from(() -> service.complete(execution, stored))
        .doOnNext(
            saved -> {
              if (!saved) LOG.warn("Idempotency key lost before completion: {}", execution.key());
            })
        .doOnError(
            error ->
                LOG.error(
                    "Idempotency store unavailable while finishing {}", execution.key(), error))
        .then();
  }

  private Mono<Void> release(IdempotencyExchange state) {
    state.closeLease();
    IdempotencyService.Proceed execution = state.execution();
    if (execution == null) return Mono.empty();
    return ReactiveStages.from(
            () ->
                state.noEffect ? service.releaseWithoutEffect(execution) : service.fail(execution))
        .doOnError(
            error ->
                LOG.error(
                    "Idempotency store unavailable while releasing {}", execution.key(), error))
        .then();
  }
}
