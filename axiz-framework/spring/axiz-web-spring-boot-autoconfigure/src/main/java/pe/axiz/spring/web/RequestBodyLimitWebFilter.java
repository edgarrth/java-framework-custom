package pe.axiz.spring.web;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Cuenta los búferes transmitidos sin retener la solicitud en memoria. */
public final class RequestBodyLimitWebFilter implements WebFilter, Ordered {
  private final RequestBodyLimits limits;

  RequestBodyLimitWebFilter(RequestBodyLimits limits) {
    this.limits = limits;
  }

  @Override
  public int getOrder() {
    return RequestMetadataFilter.ORDER + 1;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    return Mono.defer(
        () -> {
          long limit =
              limits.forPath(exchange.getRequest().getPath().pathWithinApplication().value());
          if (exchange.getRequest().getHeaders().getContentLength() > limit)
            return reject(exchange);
          var count = new AtomicLong();
          var request =
              new ServerHttpRequestDecorator(exchange.getRequest()) {
                @Override
                public Flux<DataBuffer> getBody() {
                  return super.getBody()
                      .<DataBuffer>handle(
                          (buffer, sink) -> {
                            if (count.addAndGet(buffer.readableByteCount()) > limit) {
                              DataBufferUtils.release(buffer);
                              sink.error(new RequestBodyLimits.BodyTooLarge());
                            } else sink.next(buffer);
                          })
                      .doOnDiscard(DataBuffer.class, DataBufferUtils::release);
                }
              };
          return chain
              .filter(exchange.mutate().request(request).build())
              .onErrorResume(RequestBodyLimits::exceeded, failure -> reject(exchange));
        });
  }

  private static Mono<Void> reject(ServerWebExchange exchange) {
    if (exchange.getResponse().isCommitted())
      return Mono.error(new RequestBodyLimits.BodyTooLarge());
    exchange.getResponse().setStatusCode(HttpStatus.CONTENT_TOO_LARGE);
    exchange
        .getResponse()
        .getHeaders()
        .setContentType(MediaType.parseMediaType("application/problem+json"));
    return exchange
        .getResponse()
        .writeWith(
            Mono.just(
                exchange
                    .getResponse()
                    .bufferFactory()
                    .wrap(RequestBodyLimits.problem().getBytes(StandardCharsets.UTF_8))));
  }
}
