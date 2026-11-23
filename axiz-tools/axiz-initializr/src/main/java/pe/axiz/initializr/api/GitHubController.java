package pe.axiz.initializr.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.ClientAuthorizationRequiredException;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pe.axiz.codegen.output.GeneratedProject;
import pe.axiz.initializr.config.EphemeralAuthorizedClients;
import pe.axiz.initializr.config.InitializrProperties;
import pe.axiz.initializr.config.SecurityConfiguration;
import pe.axiz.initializr.service.GenerationService;
import pe.axiz.initializr.service.GitHubPublisher;

/**
 * Crea el repositorio de un proyecto nuevo en GitHub con la autorización propia del usuario
 * (registro de cliente OAuth {@code github}); el token se usa solo para esta solicitud.
 */
@RestController
public class GitHubController {

  private final GenerationService generation;
  private final GitHubPublisher publisher;
  private final InitializrProperties properties;
  private final EphemeralAuthorizedClients authorizedClients;

  public GitHubController(
      GenerationService generation,
      GitHubPublisher publisher,
      InitializrProperties properties,
      EphemeralAuthorizedClients authorizedClients) {
    this.generation = generation;
    this.publisher = publisher;
    this.properties = properties;
    this.authorizedClients = authorizedClients;
  }

  /** Genera el proyecto y lo sube a un repositorio privado nuevo. */
  @PostMapping("/v1/projects/github")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, String> create(
      @Valid @RequestBody ProjectRequestBody body,
      @RequestHeader("Idempotency-Key") UUID operation,
      Authentication principal,
      HttpServletRequest request,
      HttpServletResponse response) {
    GitHubPublisher.validateDescription(body.description());
    if (authorizedClients.loadAuthorizedClient(SecurityConfiguration.GITHUB, principal, request)
        == null) throw new ClientAuthorizationRequiredException(SecurityConfiguration.GITHUB);
    GeneratedProject project =
        generation.generate(body.toRequest(generation.catalog(), properties.frameworkVersion()));
    publisher.validate(project, body.description());
    OAuth2AuthorizedClient github = authorizedClients.consume(principal, request, response);
    if (github == null)
      throw new ClientAuthorizationRequiredException(SecurityConfiguration.GITHUB);
    GitHubPublisher.Repository repo =
        publisher.publish(
            project,
            github.getAccessToken().getTokenValue(),
            body.description(),
            operation,
            principal.getName());
    return Map.of(
        "repository", repo.fullName(), "url", repo.url(), "operationId", operation.toString());
  }

  /**
   * Destino de la autorización de GitHub: el soporte de cliente OAuth ya la almacenó para el
   * usuario autenticado y redirige aquí; la UI continúa donde estaba.
   */
  @GetMapping(SecurityConfiguration.GITHUB_CALLBACK)
  public ResponseEntity<Void> authorized() {
    return ResponseEntity.status(HttpStatus.SEE_OTHER)
        .location(URI.create("/?github=authorized"))
        .build();
  }
}
