package pe.axiz.initializr.api;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pe.axiz.codegen.output.GeneratedProject;
import pe.axiz.initializr.config.InitializrProperties;
import pe.axiz.initializr.config.SecurityConfiguration;
import pe.axiz.initializr.service.GenerationService;
import pe.axiz.initializr.service.GitHubPublisher;

/**
 * Creates the repository of a new project in GitHub with the user's own authorization (OAuth client
 * registration {@code github}); the token is used for this request only.
 */
@RestController
public class GitHubController {

  private final GenerationService generation;
  private final GitHubPublisher publisher;
  private final InitializrProperties properties;

  public GitHubController(
      GenerationService generation, GitHubPublisher publisher, InitializrProperties properties) {
    this.generation = generation;
    this.publisher = publisher;
    this.properties = properties;
  }

  /** Generates the project and pushes it to a new private repository. */
  @PostMapping("/v1/projects/github")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, String> create(
      @Valid @RequestBody ProjectRequestBody body,
      @RegisteredOAuth2AuthorizedClient(SecurityConfiguration.GITHUB)
          OAuth2AuthorizedClient github) {
    GeneratedProject project =
        generation.generate(body.toRequest(generation.catalog(), properties.frameworkVersion()));
    GitHubPublisher.Repository repo =
        publisher.publish(project, github.getAccessToken().getTokenValue(), body.description());
    return Map.of("repository", repo.fullName(), "url", repo.url());
  }

  /**
   * Landing of the GitHub authorization: the OAuth client support has already stored it for the
   * signed-in user and redirects here; the UI picks up where it was.
   */
  @GetMapping(SecurityConfiguration.GITHUB_CALLBACK)
  public ResponseEntity<Void> authorized() {
    return ResponseEntity.status(HttpStatus.SEE_OTHER)
        .location(URI.create("/?github=authorized"))
        .build();
  }
}
