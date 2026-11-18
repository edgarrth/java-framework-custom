package pe.axiz.spring.web;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import pe.axiz.core.api.StandardHeaders;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.context.RequestMetadata;
import pe.axiz.core.errors.Problem;
import pe.axiz.core.errors.ProblemFactory;
import reactor.core.publisher.Mono;

/**
 * Reactive counterpart of {@link RequestMetadataFilter}. The metadata travels in the Reactor
 * context under {@link RequestContext#KEY}; context-propagation restores it as a thread-local where
 * needed, without global Reactor hooks of our own.
 */
public final class RequestMetadataWebFilter implements WebFilter, Ordered {

  private final RequestMetadataResolver resolver;
  private final @Nullable ProblemFactory problems;

  public RequestMetadataWebFilter(
      RequestMetadataResolver resolver, @Nullable ProblemFactory problems) {
    this.resolver = Objects.requireNonNull(resolver);
    this.problems = problems;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    HttpHeaders headers = exchange.getRequest().getHeaders();
    Locale locale = exchange.getLocaleContext().getLocale();
    RequestMetadata metadata =
        resolver.resolve(headers::getFirst, locale != null ? locale : Locale.ROOT);
    exchange
        .getResponse()
        .getHeaders()
        .set(StandardHeaders.CORRELATION_ID, metadata.correlationId());
    String path = exchange.getRequest().getPath().pathWithinApplication().value();
    List<String> invalid = resolver.invalidHeaders(path, headers::getFirst);
    if (!invalid.isEmpty()) {
      byte[] body =
          HeaderProblemWriter.body(
                  problems,
                  invalid,
                  metadata.locale(),
                  exchange.getRequest().getPath().value(),
                  metadata.correlationId())
              .getBytes(StandardCharsets.UTF_8);
      exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
      exchange
          .getResponse()
          .getHeaders()
          .setContentType(MediaType.parseMediaType(Problem.MEDIA_TYPE));
      DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
      return exchange.getResponse().writeWith(Mono.just(buffer));
    }
    exchange.getAttributes().put(RequestContext.KEY, metadata);
    return chain
        .filter(exchange)
        .contextWrite(
            ctx ->
                ctx.put(RequestContext.KEY, metadata)
                    .put(MdcKeys.CORRELATION_ID, metadata.correlationId()));
  }

  @Override
  public int getOrder() {
    return RequestMetadataFilter.ORDER;
  }
}
