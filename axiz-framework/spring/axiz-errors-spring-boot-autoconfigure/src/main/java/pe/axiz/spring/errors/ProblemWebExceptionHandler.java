package pe.axiz.spring.errors;

import java.nio.charset.StandardCharsets;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import pe.axiz.core.commons.StandardHeaders;
import pe.axiz.core.errors.Problem;
import reactor.core.publisher.Mono;

/** WebFlux exception handler producing RFC 9457 problems (runs before Boot's default handler). */
public class ProblemWebExceptionHandler implements WebExceptionHandler {

  private final ProblemResponder responder;

  public ProblemWebExceptionHandler(ProblemResponder responder) {
    this.responder = responder;
  }

  @Override
  public Mono<Void> handle(ServerWebExchange exchange, Throwable failure) {
    ServerHttpResponse response = exchange.getResponse();
    if (response.isCommitted()) {
      return Mono.error(failure);
    }
    Problem problem =
        responder.problem(
            failure,
            exchange.getLocaleContext().getLocale() != null
                ? exchange.getLocaleContext().getLocale()
                : java.util.Locale.ROOT,
            exchange.getRequest().getPath().value(),
            exchange.getRequest().getHeaders().getFirst(StandardHeaders.CORRELATION_ID));
    response.setStatusCode(HttpStatusCode.valueOf(problem.status()));
    response.getHeaders().setContentType(MediaType.parseMediaType(ProblemResponder.MEDIA_TYPE));
    DataBuffer buffer =
        response.bufferFactory().wrap(responder.json(problem).getBytes(StandardCharsets.UTF_8));
    return response.writeWith(Mono.just(buffer));
  }
}
