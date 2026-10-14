package pe.axiz.spring.security;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.server.ServerWebExchange;
import pe.axiz.core.errors.Problem;
import reactor.core.publisher.Mono;

/** Reactive 401 and 403 handling with problem bodies. */
public class ReactiveProblemHandlers
    implements ServerAuthenticationEntryPoint, ServerAccessDeniedHandler {

  private final SecurityProblems problems;

  public ReactiveProblemHandlers(SecurityProblems problems) {
    this.problems = Objects.requireNonNull(problems);
  }

  @Override
  public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
    boolean hadToken =
        exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION) != null;
    exchange
        .getResponse()
        .getHeaders()
        .set(HttpHeaders.WWW_AUTHENTICATE, hadToken ? "Bearer error=\"invalid_token\"" : "Bearer");
    return write(
        exchange,
        HttpStatus.UNAUTHORIZED,
        problems.unauthorized(locale(exchange), exchange.getRequest().getPath().value()));
  }

  @Override
  public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException denied) {
    exchange
        .getResponse()
        .getHeaders()
        .set(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\"");
    return write(
        exchange,
        HttpStatus.FORBIDDEN,
        problems.forbidden(locale(exchange), exchange.getRequest().getPath().value()));
  }

  static Locale locale(ServerWebExchange exchange) {
    Locale l = exchange.getLocaleContext().getLocale();
    return l != null ? l : Locale.ROOT;
  }

  static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String body) {
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
                    .wrap(body.getBytes(StandardCharsets.UTF_8))));
  }
}
