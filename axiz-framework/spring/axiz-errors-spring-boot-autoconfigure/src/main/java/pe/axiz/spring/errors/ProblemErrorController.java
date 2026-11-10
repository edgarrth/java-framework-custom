package pe.axiz.spring.errors;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import pe.axiz.core.commons.StandardHeaders;
import pe.axiz.core.errors.Problem;

/**
 * Replaces Boot's whitelabel error controller: errors raised in filters or by the container are
 * rendered as RFC 9457 problems too.
 *
 * <p>The container forwards a failed request to the error path keeping its HTTP method, so the
 * error page answers every method. The methods are listed explicitly and split in two mappings,
 * safe and unsafe, instead of one mapping open to any method: rendering an error changes no state,
 * and a method outside the list is never routed here.
 */
@RestController
public class ProblemErrorController implements ErrorController {

  private final ProblemResponder responder;

  public ProblemErrorController(ProblemResponder responder) {
    this.responder = responder;
  }

  /**
   * The error of a request made with a safe method.
   *
   * @param request the forwarded request
   * @return the problem
   */
  @RequestMapping(
      path = "${server.error.path:${error.path:/error}}",
      method = {RequestMethod.GET, RequestMethod.HEAD, RequestMethod.OPTIONS, RequestMethod.TRACE})
  ResponseEntity<String> safeMethodError(HttpServletRequest request) {
    return error(request);
  }

  /**
   * The error of a request made with a method that changes state. Rendering it changes nothing.
   *
   * @param request the forwarded request
   * @return the problem
   */
  @RequestMapping(
      path = "${server.error.path:${error.path:/error}}",
      method = {RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
  ResponseEntity<String> unsafeMethodError(HttpServletRequest request) {
    return error(request);
  }

  ResponseEntity<String> error(HttpServletRequest request) {
    Object exception = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
    Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
    Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
    Throwable failure =
        exception instanceof Throwable t
            ? t
            : new ResponseStatusException(
                HttpStatusCode.valueOf(status instanceof Integer i ? i : 500));
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
