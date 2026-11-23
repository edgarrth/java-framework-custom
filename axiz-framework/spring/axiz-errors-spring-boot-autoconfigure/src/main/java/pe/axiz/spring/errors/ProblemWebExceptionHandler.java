package pe.axiz.spring.errors;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import pe.axiz.core.api.StandardHeaders;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.context.RequestMetadata;
import pe.axiz.core.errors.Problem;
import pe.axiz.spring.foundation.ReactiveRenderContext;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/**
 * Manejador de excepciones de WebFlux que produce problemas RFC 9457 (se ejecuta antes del
 * manejador por defecto de Boot).
 */
public class ProblemWebExceptionHandler implements WebExceptionHandler {
  private final ProblemResponder responder;
  private final Scheduler scheduler;

  /** La aplicación posee el scheduler acotado entregado y lo libera al apagarse. */
  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Validates borrowed collaborators only; no resource is acquired or this published. "
              + "The public handler remains extensible.")
  public ProblemWebExceptionHandler(ProblemResponder responder, Scheduler scheduler) {
    this.responder = Objects.requireNonNull(responder);
    this.scheduler = Objects.requireNonNull(scheduler);
  }

  @Override
  public Mono<Void> handle(ServerWebExchange exchange, Throwable failure) {
    return Mono.deferContextual(
        context -> {
          if (exchange.getResponse().isCommitted()) return Mono.error(failure);
          RequestMetadata metadata =
              context.getOrDefault(RequestContext.KEY, exchange.getAttribute(RequestContext.KEY));
          long retryAfter = Math.max(1, ProblemResponder.retryAfterSeconds(failure));
          return Mono.fromSupplier(
                  () ->
                      ReactiveRenderContext.call(
                          context, metadata, () -> render(exchange, failure, metadata)))
              .subscribeOn(scheduler)
              .flatMap(rendered -> write(exchange, failure, rendered))
              .onErrorResume(
                  RejectedExecutionException.class, rejected -> unavailable(exchange, retryAfter));
        });
  }

  private Rendered render(
      ServerWebExchange exchange, Throwable failure, @Nullable RequestMetadata metadata) {
    Problem problem =
        responder.problem(
            failure,
            exchange.getLocaleContext().getLocale() != null
                ? exchange.getLocaleContext().getLocale()
                : Locale.ROOT,
            exchange.getRequest().getPath().value(),
            metadata != null
                ? metadata.correlationId()
                : exchange.getRequest().getHeaders().getFirst(StandardHeaders.CORRELATION_ID));
    return new Rendered(problem, responder.json(problem));
  }

  private static Mono<Void> unavailable(ServerWebExchange exchange, long retryAfter) {
    var response = exchange.getResponse();
    if (response.isCommitted())
      return Mono.error(new RejectedExecutionException("Rendering unavailable"));
    response.setStatusCode(HttpStatusCode.valueOf(503));
    response.getHeaders().setContentType(MediaType.parseMediaType(ProblemResponder.MEDIA_TYPE));
    response.getHeaders().setCacheControl("no-store");
    response.getHeaders().set("Retry-After", Long.toString(retryAfter));
    byte[] body =
        "{\"type\":\"about:blank\",\"title\":\"Service Unavailable\",\"status\":503}"
            .getBytes(StandardCharsets.UTF_8);
    return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
  }

  private Mono<Void> write(ServerWebExchange exchange, Throwable failure, Rendered rendered) {
    ServerHttpResponse response = exchange.getResponse();
    if (response.isCommitted()) return Mono.error(failure);
    responder.headers(response.getHeaders(), rendered.problem(), failure);
    response.setStatusCode(HttpStatusCode.valueOf(rendered.problem().status()));
    response.getHeaders().setContentType(MediaType.parseMediaType(ProblemResponder.MEDIA_TYPE));
    DataBuffer buffer =
        response.bufferFactory().wrap(rendered.json().getBytes(StandardCharsets.UTF_8));
    return response.writeWith(Mono.just(buffer));
  }

  private record Rendered(Problem problem, String json) {}
}
