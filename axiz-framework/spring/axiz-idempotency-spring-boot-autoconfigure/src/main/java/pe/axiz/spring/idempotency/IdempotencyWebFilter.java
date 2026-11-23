package pe.axiz.spring.idempotency;

import java.util.Arrays;
import java.util.List;
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
import pe.axiz.core.api.HttpPayloadPolicy;
import pe.axiz.core.api.StandardHeaders;
import pe.axiz.core.crypto.AsyncPayloadProtector;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.idempotency.AsyncIdempotencyService;
import pe.axiz.core.idempotency.IdempotencyErrors;
import pe.axiz.core.idempotency.IdempotencyService;
import pe.axiz.core.idempotency.StoredResponse;
import pe.axiz.spring.foundation.ReactiveStages;
import pe.axiz.spring.idempotency.ReactiveIdempotency.IdempotencyExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

/**
 * Contraparte reactiva de {@link IdempotencyFilter}: para las solicitudes no seguras calcula la
 * huella del cuerpo en búfer (método, ruta, query string tal como se envió y cuerpo) y la entrega
 * al advice mediante el contexto de Reactor; el advice decide después de la autorización de
 * métodos. Cuando el handler continuó, se almacena la respuesta capturada (hasta {@code
 * maxResponseSize}); un cuerpo mayor igualmente se transmite al cliente y solo se almacenan su
 * estado y sus cabeceras.
 */
public final class IdempotencyWebFilter implements WebFilter, Ordered {

  /** Después del {@code WebFilterChainProxy} de Spring Security (orden -100). */
  public static final int ORDER = Ordered.LOWEST_PRECEDENCE - 100;

  private static final Logger LOG = LoggerFactory.getLogger(IdempotencyWebFilter.class);

  private final AsyncIdempotencyService service;
  private final int maxBodySize;
  private final int maxResponseSize;
  private final List<HttpPayloadPolicy> payloadPolicies;
  private final AsyncPayloadProtector protection;

  public IdempotencyWebFilter(
      AsyncIdempotencyService service,
      int maxBodySize,
      int maxResponseSize,
      List<HttpPayloadPolicy> payloadPolicies) {
    this(service, maxBodySize, maxResponseSize, payloadPolicies, AsyncPayloadProtector.NONE);
  }

  public IdempotencyWebFilter(
      AsyncIdempotencyService service,
      int maxBodySize,
      int maxResponseSize,
      List<HttpPayloadPolicy> payloadPolicies,
      AsyncPayloadProtector protection) {
    this.protection = Objects.requireNonNull(protection);
    this.service = Objects.requireNonNull(service);
    this.maxBodySize = maxBodySize;
    this.maxResponseSize = maxResponseSize;
    this.payloadPolicies = List.copyOf(payloadPolicies);
  }

  @Override
  public int getOrder() {
    return ORDER;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String method = exchange.getRequest().getMethod().name();
    if (!IdempotencySupport.unsafe(method)
        || payloadPolicies.stream()
            .anyMatch(
                p ->
                    !p.permitsCapture(
                        exchange.getRequest().getPath().pathWithinApplication().value()))) {
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
            // las respuestas transmitidas (con flush) se almacenan sin cuerpo: no se admite
            // reproducir un stream, sí su estado y sus cabeceras
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
            resource -> release(resource))
        .doFinally(
            signal -> {
              captured.close();
              Arrays.fill(body, (byte) 0);
            });
  }

  private Mono<Void> finish(
      IdempotencyExchange state, ServerHttpResponseDecorator response, BoundedCapture captured) {
    IdempotencyService.Proceed execution = state.execution();
    if (execution == null) {
      return Mono.empty(); // no idempotente, rechazada antes de decidir, o una reproducción
    }
    if (state.noEffect)
      return ReactiveStages.from(() -> service.releaseWithoutEffect(execution))
          .then()
          .doFinally(signal -> state.closeLease());
    HttpStatusCode status = response.getStatusCode();
    int code = status != null ? status.value() : HttpStatus.OK.value();
    if (code >= 500) {
      return release(state);
    }
    Mono<StoredResponse> stored =
        ReactiveStages.from(
            () ->
                IdempotencyPayloads.store(
                    code,
                    captured.overflowed()
                        ? StoredResponse.withoutBody(
                                code,
                                IdempotencySupport.replayedHeaders(response.getHeaders()::getFirst))
                            .headers()
                        : IdempotencySupport.replayedHeaders(response.getHeaders()::getFirst),
                    captured.overflowed() ? new byte[0] : captured.bytes(),
                    Objects.requireNonNull(state.binding, "Missing idempotency record binding"),
                    protection));
    return stored
        .flatMap(value -> ReactiveStages.from(() -> service.complete(execution, value)))
        .doOnNext(
            saved -> {
              if (!saved) LOG.warn("Idempotency lease lost before completion");
            })
        .doOnError(
            error ->
                LOG.error(
                    "Idempotency completion failed; failureType={}",
                    error.getClass().getSimpleName()))
        .onErrorResume(
            failure ->
                ReactiveStages.from(() -> service.fail(execution))
                    .onErrorComplete()
                    .then(
                        Mono.error(
                            new BusinessException(
                                IdempotencyErrors.OUTCOME_UNKNOWN,
                                "Idempotency completion outcome unknown"))))
        .then()
        .doFinally(signal -> state.closeLease());
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
                    "Idempotency release failed; failureType={}", error.getClass().getSimpleName()))
        .then();
  }
}
