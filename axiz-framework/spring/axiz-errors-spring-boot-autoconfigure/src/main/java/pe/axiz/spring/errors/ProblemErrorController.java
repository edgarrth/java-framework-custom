package pe.axiz.spring.errors;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import pe.axiz.core.commons.StandardHeaders;
import pe.axiz.core.errors.Problem;

/**
 * Replaces Boot's whitelabel error controller: errors raised in filters or by the container are
 * rendered as RFC 9457 problems too.
 */
@RestController
public class ProblemErrorController implements ErrorController {

  private final ProblemResponder responder;

  public ProblemErrorController(ProblemResponder responder) {
    this.responder = responder;
  }

  @RequestMapping("${server.error.path:${error.path:/error}}")
  ResponseEntity<String> error(HttpServletRequest request) {
    Object exception = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
    Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
    Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
    Throwable failure =
        exception instanceof Throwable t
            ? t
            : new ResponseStatusException(
                org.springframework.http.HttpStatusCode.valueOf(
                    status instanceof Integer i ? i : 500));
    Problem problem =
        responder.problem(
            failure,
            LocaleContextHolder.getLocale(),
            uri == null ? null : uri.toString(),
            request.getHeader(StandardHeaders.CORRELATION_ID));
    return ResponseEntity.status(problem.status())
        .contentType(MediaType.parseMediaType(ProblemResponder.MEDIA_TYPE))
        .body(responder.json(problem));
  }
}
