package pe.axiz.initializr.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.ClientAuthorizationRequiredException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import pe.axiz.codegen.output.GeneratedProject;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditPublisher;
import pe.axiz.core.commons.TransactionOutcome;
import pe.axiz.initializr.config.InitializrProperties;
import pe.axiz.initializr.config.SecurityConfiguration;
import pe.axiz.initializr.service.PublicationException.Phase;

/** Resumable publication of one authenticated operation into its own private repository. */
@Service
public class GitHubPublisher {
  /** Confirmed publication destination, without credentials or generated content. */
  public record Repository(String fullName, String url) {}

  private final RestClient.Builder clients;
  private final InitializrProperties properties;
  private final PublicationIdentity identities;
  private final AuditPublisher audit;

  public GitHubPublisher(
      @Qualifier("githubRestClient") RestClient.Builder clients,
      InitializrProperties properties,
      PublicationIdentity identities,
      AuditPublisher audit) {
    this.clients = clients;
    this.properties = properties;
    this.identities = identities;
    this.audit = audit;
  }

  /**
   * Resumes only repositories bearing this operation's authenticated marker and project digest.
   * Other repositories and intervening user commits are never adopted, forced or deleted.
   */
  public Repository publish(
      GeneratedProject project,
      String token,
      @Nullable String description,
      UUID operation,
      String principal) {
    String name = identifier(project.rootName());
    List<Map<String, Object>> entries = prepare(project, description);
    RestClient github =
        clients
            .clone()
            .baseUrl(properties.githubApi().toString())
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
            .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
            .build();
    ResponseEntity<Map<String, Object>> user;
    try {
      user = github.get().uri("/user").retrieve().toEntity(new ParameterizedTypeReference<>() {});
    } catch (HttpClientErrorException denied) {
      if (denied.getStatusCode() == HttpStatus.UNAUTHORIZED)
        throw new ClientAuthorizationRequiredException(SecurityConfiguration.GITHUB);
      Phase deniedPhase =
          denied.getStatusCode() == HttpStatus.FORBIDDEN && !rateLimited(denied)
              ? Phase.CONFLICT
              : Phase.PREFLIGHT;
      throw new PublicationException(operation, deniedPhase, name, denied);
    } catch (RuntimeException failure) {
      throw new PublicationException(operation, Phase.PREFLIGHT, name, failure);
    }
    String granted = user.getHeaders().getFirst("X-OAuth-Scopes");
    Set<String> scopes =
        granted == null
            ? Set.of()
            : Arrays.stream(granted.split("[, ]+"))
                .filter(scope -> !scope.isBlank())
                .collect(Collectors.toSet());
    if (!scopes.containsAll(Set.of("repo", "workflow")))
      throw new ClientAuthorizationRequiredException(SecurityConfiguration.GITHUB);
    String organization = properties.githubOrganization();
    boolean personal = organization == null || organization.isBlank();
    String owner =
        identifier(personal ? text(user.getBody(), "login") : Objects.requireNonNull(organization))
            .toLowerCase(Locale.ROOT);
    String fullName = owner + "/" + name;
    String marker = identities.marker(project, operation, principal, fullName);
    String operationDescription =
        description == null || description.isBlank() ? marker : description + " " + marker;
    String message = "feat: generate " + name + " with axiz initializr\n\n" + marker;
    Phase phase = Phase.PREFLIGHT;
    audit(operation, principal, fullName, "REQUESTED", false);
    try {
      Map<?, ?> repo;
      try {
        repo = get(github, "/repos/" + fullName);
      } catch (HttpClientErrorException missing) {
        if (missing.getStatusCode() != HttpStatus.NOT_FOUND) throw missing;
        phase = Phase.REPOSITORY_CREATE_UNKNOWN;
        repo =
            post(
                github,
                personal ? "/user/repos" : "/orgs/" + owner + "/repos",
                Map.of(
                    "name",
                    name,
                    "description",
                    operationDescription,
                    "private",
                    true,
                    "auto_init",
                    true));
      }
      phase = Phase.CONFLICT;
      if (!fullName.equalsIgnoreCase(text(repo, "full_name"))
          || !Boolean.TRUE.equals(repo.get("private"))
          || !(text(repo, "description").equals(marker)
              || text(repo, "description").endsWith(" " + marker)))
        throw new IllegalStateException("Repository is not owned by this publication operation");
      String branch = text(repo, "default_branch");
      if (!branch.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,199}") || branch.contains(".."))
        throw new IllegalStateException("Invalid repository branch");
      String url = text(repo, "html_url");
      if (!url.startsWith("https://")) throw new IllegalStateException("Invalid repository URL");
      phase = Phase.CONTENT_PENDING;
      String parent =
          sha(map(initialRef(github, "/repos/" + fullName + "/git/ref/heads/" + branch), "object"));
      Map<?, ?> parentCommit = get(github, "/repos/" + fullName + "/git/commits/" + parent);
      // A previous PATCH may have succeeded even if its response was lost.
      boolean alreadyPublished = message.equals(parentCommit.get("message"));
      Object parents = parentCommit.get("parents");
      if (!(parents instanceof List<?> ancestry))
        throw new IllegalStateException("Missing commit ancestry");
      String baseTree;
      if (alreadyPublished
          && ancestry.size() == 1
          && ancestry.getFirst() instanceof Map<?, ?> ancestor) {
        baseTree =
            sha(map(get(github, "/repos/" + fullName + "/git/commits/" + sha(ancestor)), "tree"));
      } else if (!alreadyPublished && ancestry.isEmpty()) {
        baseTree = sha(map(parentCommit, "tree"));
      } else {
        phase = Phase.CONFLICT;
        throw new IllegalStateException("Repository contains intervening changes");
      }
      String tree =
          sha(
              post(
                  github,
                  "/repos/" + fullName + "/git/trees",
                  Map.of("base_tree", baseTree, "tree", entries)));
      if (alreadyPublished) {
        if (!tree.equals(sha(map(parentCommit, "tree")))) {
          phase = Phase.CONFLICT;
          throw new IllegalStateException("Published tree differs from this project");
        }
      } else {
        String commit =
            sha(
                post(
                    github,
                    "/repos/" + fullName + "/git/commits",
                    Map.of("message", message, "tree", tree, "parents", List.of(parent))));
        phase = Phase.BRANCH_UPDATE_UNKNOWN;
        mutation(
            () ->
                github
                    .patch()
                    .uri("/repos/" + fullName + "/git/refs/heads/" + branch)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("sha", commit, "force", false))
                    .retrieve()
                    .toBodilessEntity());
        if (!commit.equals(
            sha(map(get(github, "/repos/" + fullName + "/git/ref/heads/" + branch), "object"))))
          throw new IllegalStateException("Published branch outcome changed");
      }
      audit(operation, principal, fullName, "CONFIRMED", true);
      logPublication(operation, null);
      return new Repository(fullName, url);
    } catch (RuntimeException failure) {
      if (failure instanceof Rejected || failure instanceof ClientAuthorizationRequiredException)
        phase = Phase.CONFLICT;
      if (failure instanceof Throttled) phase = Phase.CONTENT_PENDING;
      if (failure instanceof HttpClientErrorException denied) {
        if (denied.getStatusCode() == HttpStatus.UNAUTHORIZED) {
          phase = Phase.CONFLICT;
          failure = new ClientAuthorizationRequiredException(SecurityConfiguration.GITHUB);
        } else if (denied.getStatusCode() == HttpStatus.FORBIDDEN && !rateLimited(denied))
          phase = Phase.CONFLICT;
      }
      audit(operation, principal, fullName, phase.name(), true);
      logPublication(operation, phase);
      if (failure instanceof ClientAuthorizationRequiredException authorization)
        throw authorization;
      throw new PublicationException(operation, phase, fullName, failure);
    }
  }

  private void audit(
      UUID operation, String principal, String repository, String phase, boolean remoteStarted) {
    try {
      audit.publish(
          AuditEvent.builder()
              .id(UUID.randomUUID().toString())
              .occurredAt(Instant.now())
              .service("axiz-initializr")
              .action("REQUESTED".equals(phase) ? "project.publish.requested" : "project.publish")
              .resourceType("github-repository")
              .resourceId(repository)
              .actor(new AuditEvent.Actor(subject(principal), null, null))
              .correlationId(operation.toString())
              .details(Map.of("phase", phase))
              .outcome(
                  ("CONFIRMED".equals(phase) || "REQUESTED".equals(phase))
                      ? AuditEvent.Outcome.SUCCESS
                      : AuditEvent.Outcome.FAILURE)
              .transactionOutcome(TransactionOutcome.UNKNOWN)
              .operationOutcome(
                  "CONFIRMED".equals(phase)
                      ? TransactionOutcome.COMMITTED
                      : TransactionOutcome.UNKNOWN)
              .build());
    } catch (RuntimeException failure) {
      if (!remoteStarted) throw failure;
      // An audit sink failure cannot rewrite the already confirmed/uncertain remote outcome.
      LoggerFactory.getLogger(GitHubPublisher.class)
          .error("Publication audit delivery not confirmed");
    }
  }

  private static String subject(String principal) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(principal.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("Audit subject hashing unavailable", unavailable);
    }
  }

  private static Map<?, ?> initialRef(RestClient github, String path) {
    for (int attempt = 0; ; attempt++) {
      try {
        return get(github, path);
      } catch (HttpClientErrorException pending) {
        if (attempt == 2
            || (pending.getStatusCode() != HttpStatus.NOT_FOUND
                && pending.getStatusCode() != HttpStatus.CONFLICT)) throw pending;
        try {
          Thread.sleep(Duration.ofMillis(200L << attempt));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(
              "Interrupted waiting for repository initialization", interrupted);
        }
      }
    }
  }

  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification =
          "Only final JDK UUID and a locally defined enum enter this boundary; "
              + "neither contains CR/LF. No request strings, provider messages or Throwable are logged.")
  private static void logPublication(UUID operation, @Nullable Phase phase) {
    if (phase == null)
      LoggerFactory.getLogger(GitHubPublisher.class)
          .info("Project publication confirmed; operationId={}", operation);
    else
      LoggerFactory.getLogger(GitHubPublisher.class)
          .warn("Project publication incomplete; operationId={}, phase={}", operation, phase);
  }

  private static Map<?, ?> get(RestClient github, String path) {
    return required(github.get().uri(path).retrieve().body(Map.class));
  }

  private static Map<?, ?> post(RestClient github, String path, Map<String, ?> body) {
    return mutation(
        () ->
            required(
                github
                    .post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class)));
  }

  private static <T> T mutation(Supplier<T> action) {
    try {
      return action.get();
    } catch (HttpClientErrorException rejected) {
      int status = rejected.getStatusCode().value();
      if (status == 401)
        throw new ClientAuthorizationRequiredException(SecurityConfiguration.GITHUB);
      if (status == 429 || (status == 403 && rateLimited(rejected))) throw new Throttled(rejected);
      if (status == 400 || status == 403 || status == 404 || status == 409 || status == 422)
        throw new Rejected(rejected);
      throw rejected;
    }
  }

  private static boolean rateLimited(HttpClientErrorException failure) {
    HttpHeaders headers = failure.getResponseHeaders();
    return headers != null
        && (headers.getFirst(HttpHeaders.RETRY_AFTER) != null
            || "0".equals(headers.getFirst("X-RateLimit-Remaining")));
  }

  private static final class Throttled extends IllegalStateException {
    Throttled(RuntimeException cause) {
      super("GitHub temporarily limited publication requests", cause);
    }
  }

  private static final class Rejected extends IllegalStateException {
    Rejected(RuntimeException cause) {
      super("GitHub rejected publication input or state", cause);
    }
  }

  /** Pure preflight; invalid requests retain their unused delegated authorization. */
  public void validate(GeneratedProject project, @Nullable String description) {
    prepare(project, description);
  }

  /** GitHub's bounded public description has a different limit from ZIP generation. */
  public static void validateDescription(@Nullable String description) {
    if (description != null && description.length() > 160)
      throw new PublicationInputException(PublicationInputException.Field.DESCRIPTION);
  }

  private List<Map<String, Object>> prepare(
      GeneratedProject project, @Nullable String description) {
    validateDescription(description);
    if (project.paths().size() > properties.maxFiles())
      throw new PublicationInputException(PublicationInputException.Field.FILES);
    identifier(project.rootName());
    return tree(project);
  }

  private static Map<?, ?> required(@Nullable Map<?, ?> body) {
    if (body == null) throw new IllegalStateException("Incomplete GitHub response");
    return body;
  }

  private static Map<?, ?> map(Map<?, ?> body, String key) {
    if (!(body.get(key) instanceof Map<?, ?> value))
      throw new IllegalStateException("Incomplete GitHub response");
    return value;
  }

  private static String text(@Nullable Map<?, ?> body, String key) {
    if (body == null || !(body.get(key) instanceof String value) || value.isBlank())
      throw new IllegalStateException("Incomplete GitHub response");
    return value;
  }

  private static String sha(Map<?, ?> body) {
    String value = text(body, "sha");
    if (!value.matches("[0-9a-f]{40}|[0-9a-f]{64}"))
      throw new IllegalStateException("Invalid Git object identity");
    return value;
  }

  private static String identifier(String value) {
    if (!value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}"))
      throw new IllegalArgumentException("Invalid GitHub name");
    return value;
  }

  static List<Map<String, Object>> tree(GeneratedProject project) {
    List<Map<String, Object>> entries = new ArrayList<>();
    project
        .files()
        .forEach(
            (path, content) -> {
              // GeneratedProject already validates traversal, absolute paths and controls.
              if (Arrays.stream(path.split("/")).anyMatch(part -> part.equalsIgnoreCase(".git")))
                throw new PublicationInputException(PublicationInputException.Field.CONTENT);
              Map<String, Object> entry = new LinkedHashMap<>();
              entry.put("path", path);
              entry.put("mode", path.endsWith(".sh") || path.equals("mvnw") ? "100755" : "100644");
              entry.put("type", "blob");
              try {
                entry.put(
                    "content",
                    StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(content))
                        .toString());
              } catch (CharacterCodingException invalid) {
                throw new PublicationInputException(PublicationInputException.Field.CONTENT);
              }
              entries.add(entry);
            });
    return entries;
  }
}
