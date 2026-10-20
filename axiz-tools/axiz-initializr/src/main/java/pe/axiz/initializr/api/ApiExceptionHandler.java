package pe.axiz.initializr.api;

import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.oauth2.client.ClientAuthorizationRequiredException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import pe.axiz.codegen.GenerationException;

/**
 * Generation problems as RFC 9457 problem details with every problem and lint finding, and the
 * missing GitHub authorization as a problem the UI can act on (the API never redirects).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler {

  @ExceptionHandler(GenerationException.class)
  ProblemDetail invalid(GenerationException e) {
    ProblemDetail p =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.UNPROCESSABLE_CONTENT, "The project cannot be generated");
    p.setType(URI.create("https://axiz.dev/problems/invalid-project"));
    p.setTitle("Invalid project request");
    p.setProperty("problems", e.problems());
    p.setProperty("findings", ProjectController.findings(e.findings()));
    return p;
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail badRequest(IllegalArgumentException e) {
    ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    p.setTitle("Invalid request");
    return p;
  }

  /**
   * The user has not authorized GitHub yet. Handled here, before the OAuth client support would
   * answer the API call with a redirect to GitHub (which a {@code fetch} cannot follow) and
   * remember the POST to replay it as a GET after the authorization.
   */
  @ExceptionHandler(ClientAuthorizationRequiredException.class)
  ProblemDetail authorizationRequired(ClientAuthorizationRequiredException e) {
    ProblemDetail p =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.FORBIDDEN, "Authorize GitHub to create repositories in your name");
    p.setType(URI.create("https://axiz.dev/problems/authorization-required"));
    p.setTitle("Authorization required");
    p.setProperty("registration", e.getClientRegistrationId());
    p.setProperty("authorize", "/oauth2/authorization/" + e.getClientRegistrationId());
    return p;
  }
}
