package pe.axiz.spring.security;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.server.ServerWebExchange;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.context.RequestMetadata;
import pe.axiz.core.errors.Problem;
import pe.axiz.spring.foundation.ReactiveRenderContext;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/** Manejo reactivo de 401 y 403 con cuerpos de problema. */
public final class ReactiveProblemHandlers
    implements ServerAuthenticationEntryPoint, ServerAccessDeniedHandler {

  private final SecurityProblems problems;
  private final Scheduler scheduler;

  /** La aplicación posee y cierra el scheduler entregado. */
  public ReactiveProblemHandlers(SecurityProblems problems, Scheduler scheduler) {
    this.problems = Objects.requireNonNull(problems);
    this.scheduler = Objects.requireNonNull(scheduler);
  }

  @Override
  public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
    var unavailable = IssuerUnavailableException.find(ex);
    if (unavailable != null) {
      exchange
          .getResponse()
          .getHeaders()
          .set(HttpHeaders.RETRY_AFTER, Long.toString(unavailable.retryAfterSeconds()));
      exchange.getResponse().getHeaders().setCacheControl("no-store");
      return write(
          scheduler,
          exchange,
          HttpStatus.SERVICE_UNAVAILABLE,
          () -> problems.unavailable(locale(exchange), exchange.getRequest().getPath().value()));
    }
    boolean hadToken =
        exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION) != null;
    exchange
        .getResponse()
        .getHeaders()
        .set(HttpHeaders.WWW_AUTHENTICATE, hadToken ? "Bearer error=\"invalid_token\"" : "Bearer");
    return write(
        scheduler,
        exchange,
        HttpStatus.UNAUTHORIZED,
        () -> problems.unauthorized(locale(exchange), exchange.getRequest().getPath().value()));
  }

  @Override
  public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException denied) {
    exchange
        .getResponse()
        .getHeaders()
        .set(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\"");
    return write(
        scheduler,
        exchange,
        HttpStatus.FORBIDDEN,
        () -> problems.forbidden(locale(exchange), exchange.getRequest().getPath().value()));
  }

  static Locale locale(ServerWebExchange exchange) {
    Locale l = exchange.getLocaleContext().getLocale();
    return l != null ? l : Locale.ROOT;
  }

  static Mono<Void> write(
      Scheduler scheduler, ServerWebExchange exchange, HttpStatus status, Supplier<String> body) {
    // Los resolvedores de mensajes de error pueden cargar bundles o consultar a un colaborador
    // síncrono.
    return Mono.deferContextual(
            context -> {
              RequestMetadata metadata =
                  context.getOrDefault(
                      RequestContext.KEY, exchange.getAttribute(RequestContext.KEY));
              return Mono.fromSupplier(() -> ReactiveRenderContext.call(context, metadata, body))
                  .subscribeOn(scheduler);
            })
        .flatMap(
            value -> {
              exchange.getResponse().setStatusCode(status);
              exchange
                  .getResponse()
                  .getHeaders()
                  .setContentType(MediaType.parseMediaType(Problem.MEDIA_TYPE));
              return exchange
                  .getResponse()
                  .writeWith(
                      Mono.just(
                          exchange
                              .getResponse()
                              .bufferFactory()
                              .wrap(value.getBytes(StandardCharsets.UTF_8))));
            })
        .onErrorResume(RejectedExecutionException.class, rejected -> unavailable(exchange));
  }

  private static Mono<Void> unavailable(ServerWebExchange exchange) {
    var response = exchange.getResponse();
    if (response.isCommitted())
      return Mono.error(new RejectedExecutionException("Rendering unavailable"));
    response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
    response.getHeaders().remove(HttpHeaders.WWW_AUTHENTICATE);
    response.getHeaders().setContentType(MediaType.parseMediaType(Problem.MEDIA_TYPE));
    response.getHeaders().setCacheControl("no-store");
    // La cuota o el emisor pueden exigir ya una pausa más larga; la saturación no debe acortarla.
    String retryAfter = response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
    long seconds = 1;
    if (retryAfter != null) {
      try {
        seconds = Math.max(1, Long.parseLong(retryAfter));
      } catch (NumberFormatException invalid) {
        // En este límite solo se aceptan delta-seconds generados internamente.
      }
    }
    response.getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
    byte[] body =
        "{\"type\":\"about:blank\",\"title\":\"Service Unavailable\",\"status\":503}"
            .getBytes(StandardCharsets.UTF_8);
    return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
  }
}
