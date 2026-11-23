package pe.axiz.initializr.api;

import java.net.URI;
import java.util.Locale;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.oauth2.client.ClientAuthorizationRequiredException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import pe.axiz.codegen.GenerationException;
import pe.axiz.initializr.service.PublicationException;
import pe.axiz.initializr.service.PublicationInputException;

/**
 * Problemas de generación como problem details de RFC 9457 con todos los problemas y hallazgos de
 * lint, y la falta de autorización de GitHub como un problema sobre el que la UI puede actuar (la
 * API nunca redirige).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler {

  @ExceptionHandler(PublicationInputException.class)
  ProblemDetail publicationInput(PublicationInputException failure) {
    String detail =
        switch (failure.field()) {
          case DESCRIPTION -> "GitHub description must not exceed 160 characters";
          case FILES -> "Generated project exceeds the configured file limit";
          case CONTENT -> "Generated files contain unsupported paths or binary content";
        };
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    problem.setTitle("Invalid publication input");
    problem.setProperty("field", failure.field().name().toLowerCase(Locale.ROOT));
    return problem;
  }

  @ExceptionHandler(PublicationException.class)
  ProblemDetail publicationIncomplete(PublicationException failure) {
    boolean conflict = failure.phase() == PublicationException.Phase.CONFLICT;
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            conflict ? HttpStatus.CONFLICT : HttpStatus.SERVICE_UNAVAILABLE,
            conflict
                ? "GitHub rejected the operation or repository state changed; automatic publication stopped"
                : "Publication is incomplete. Authorize GitHub and retry with the same operation and project");
    problem.setTitle("Publication incomplete");
    problem.setProperty("operationId", failure.operation().toString());
    problem.setProperty("phase", failure.phase().name());
    problem.setProperty("repository", failure.repository());
    return problem;
  }

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
    ProblemDetail p =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request parameters");
    p.setTitle("Invalid request");
    return p;
  }

  /**
   * El usuario todavía no autorizó GitHub. Se atiende aquí, previo a que el soporte de cliente
   * OAuth responda la llamada a la API con una redirección a GitHub (que un {@code fetch} no puede
   * seguir) y recuerde el POST para reproducirlo como un GET tras la autorización.
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
