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
import pe.axiz.core.api.StandardHeaders;
import pe.axiz.core.errors.Problem;

/**
 * Reemplaza el controlador de errores whitelabel de Boot: los errores lanzados en filtros o por el
 * contenedor también se representan como problemas RFC 9457.
 *
 * <p>El contenedor reenvía una solicitud fallida a la ruta de error conservando su método HTTP, por
 * lo que la página de error responde a todos los métodos. Los métodos se listan explícitamente y se
 * dividen en dos mapeos, seguros e inseguros, en lugar de un único mapeo abierto a cualquier
 * método: representar un error no cambia ningún estado, y un método fuera de la lista nunca se
 * enruta aquí.
 */
@RestController
public class ProblemErrorController implements ErrorController {

  private final ProblemResponder responder;

  public ProblemErrorController(ProblemResponder responder) {
    this.responder = responder;
  }

  /**
   * Devuelve el error de una solicitud hecha con un método seguro.
   *
   * @param request la solicitud reenviada
   * @return el problema
   */
  @RequestMapping(
      path = "${server.error.path:${error.path:/error}}",
      method = {RequestMethod.GET, RequestMethod.HEAD, RequestMethod.OPTIONS, RequestMethod.TRACE})
  ResponseEntity<String> safeMethodError(HttpServletRequest request) {
    return error(request);
  }

  /**
   * Devuelve el error de una solicitud hecha con un método que cambia estado. Representarlo no
   * cambia nada.
   *
   * @param request la solicitud reenviada
   * @return el problema
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
        .headers(headers -> responder.headers(headers, problem, failure))
        .contentType(MediaType.parseMediaType(ProblemResponder.MEDIA_TYPE))
        .body(responder.json(problem));
  }
}
