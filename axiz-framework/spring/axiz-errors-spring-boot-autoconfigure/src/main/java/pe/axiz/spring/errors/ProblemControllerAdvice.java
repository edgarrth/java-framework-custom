package pe.axiz.spring.errors;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import pe.axiz.core.api.StandardHeaders;
import pe.axiz.core.errors.Problem;

/** Convierte cada excepción lanzada por los handlers de MVC en una respuesta RFC 9457. */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class ProblemControllerAdvice {

  private final ProblemResponder responder;

  public ProblemControllerAdvice(ProblemResponder responder) {
    this.responder = responder;
  }

  @ExceptionHandler(Throwable.class)
  ResponseEntity<String> handle(Throwable failure, HttpServletRequest request) {
    Throwable effectiveFailure = SpringExceptionMappers.servletSecurityFailure(failure);
    Problem problem =
        responder.problem(
            effectiveFailure,
            LocaleContextHolder.getLocale(),
            request.getRequestURI(),
            request.getHeader(StandardHeaders.CORRELATION_ID));
    return ResponseEntity.status(problem.status())
        .headers(
            headers -> {
              if (effectiveFailure != failure) headers.set("WWW-Authenticate", "Bearer");
              responder.headers(headers, problem, effectiveFailure);
            })
        .contentType(MediaType.parseMediaType(ProblemResponder.MEDIA_TYPE))
        .body(responder.json(problem));
  }
}
