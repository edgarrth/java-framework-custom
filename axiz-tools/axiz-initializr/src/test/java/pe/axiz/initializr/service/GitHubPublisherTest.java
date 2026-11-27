package pe.axiz.initializr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.security.oauth2.client.ClientAuthorizationRequiredException;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import pe.axiz.codegen.output.GeneratedProject;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.initializr.config.InitializrProperties;
import tools.jackson.databind.json.JsonMapper;

class GitHubPublisherTest {
  private static final UUID OPERATION = UUID.fromString("4a096e3a-981f-4ef5-9a33-df14a9234632");
  private static final String PRINCIPAL = "corporate-user-17";
  private static final String INITIAL = "a".repeat(40);
  private static final String BASE_TREE = "b".repeat(40);
  private static final String TREE = "c".repeat(40);
  private static final String COMMIT = "d".repeat(40);

  static GeneratedProject project(String... paths) {
    Map<String, byte[]> files = new LinkedHashMap<>();
    for (String path : paths) files.put(path, "x".getBytes(StandardCharsets.UTF_8));
    return new GeneratedProject("orders", files);
  }

  @Test
  void publishesPrivateRepositoryWithWorkflowsAndExecutableFilesAndCanRepeat() {
    GitHub github = new GitHub(null);
    GeneratedProject project = project("mvnw", "README.md", ".github/workflows/ci.yaml");
    assertThat(github.publish(project).fullName()).isEqualTo("ana/orders");
    assertThat(github.publish(project).url()).isEqualTo("https://github.test/ana/orders");
    assertThat(github.creations).isEqualTo(1);
    assertThat(github.updates).isEqualTo(1);
    assertThat(github.entries.toArray())
        .contains(
            Map.of("path", "mvnw", "mode", "100755", "type", "blob", "content", "x"),
            Map.of(
                "path",
                ".github/workflows/ci.yaml",
                "mode",
                "100644",
                "type",
                "blob",
                "content",
                "x"));
  }

  enum LostResponse {
    REPOSITORY,
    TREE,
    COMMIT,
    REF
  }

  @ParameterizedTest
  @EnumSource(LostResponse.class)
  void resumesAfterAnAcceptedWriteWhoseResponseWasLost(LostResponse after) {
    GitHub github = new GitHub("acme");
    github.lose = after;
    GeneratedProject project = project("pom.xml", ".github/workflows/ci.yaml");
    assertThatThrownBy(() -> github.publish(project))
        .isInstanceOf(PublicationException.class)
        .satisfies(
            failure ->
                assertThat(((PublicationException) failure).operation()).isEqualTo(OPERATION));
    assertThat(github.publish(project).fullName()).isEqualTo("acme/orders");
    assertThat(github.creations).isEqualTo(1);
    assertThat(github.updates).isEqualTo(1);
    assertThat(github.head).isEqualTo(COMMIT);
  }

  @Test
  void refusesAnExistingRepositoryWithoutTheAuthenticatedOperationMarker() {
    GitHub github = new GitHub(null);
    github.description = "User repository";
    assertConflict(() -> github.publish(project("pom.xml")));
    assertThat(github.writes).isZero();
  }

  @Test
  void bindsRecoveryToThePrincipalProjectAndOperation() {
    GitHub github = new GitHub(null);
    GeneratedProject project = project("pom.xml");
    github.publish(project);
    int writes = github.writes;
    assertConflict(
        () -> github.publisher.publish(project, "test-token", null, OPERATION, "other-user"));
    assertConflict(() -> github.publish(project("README.md")));
    assertConflict(
        () -> github.publisher.publish(project, "test-token", null, UUID.randomUUID(), PRINCIPAL));
    assertThat(github.writes).isEqualTo(writes);
  }

  @Test
  void neverOverwritesInterveningUserCommits() {
    GitHub github = new GitHub(null);
    GeneratedProject project = project("pom.xml");
    github.publish(project);
    github.commitMessage = "User changes";
    int writes = github.writes;
    assertConflict(() -> github.publish(project));
    assertThat(github.writes).isEqualTo(writes);
  }

  @Test
  void workflowPermissionIsCheckedBeforeAnyMutation() {
    GitHub github = new GitHub(null);
    github.scopes = "repo";
    assertThatThrownBy(() -> github.publish(project(".github/workflows/ci.yaml")))
        .isInstanceOf(ClientAuthorizationRequiredException.class);
    assertThat(github.writes).isZero();
  }

  @Test
  void validatesFilesAndKeyBeforeMutatingGitHub() {
    GitHub github = new GitHub(null);
    assertThatThrownBy(
            () ->
                github.publish(
                    new GeneratedProject("orders", Map.of("binary", new byte[] {(byte) 0xff}))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                github.publisher.publish(
                    project("pom.xml"), "test-token", "x".repeat(161), OPERATION, PRINCIPAL))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PublicationIdentity(""))
        .isInstanceOf(IllegalArgumentException.class);
    GitHubPublisher small =
        new GitHubPublisher(
            github.builder,
            new InitializrProperties(
                "2.0.0", URI.create("https://api.github.test"), null, 1, "axiz-initializr"),
            github.identities,
            github.events::add);
    assertThatThrownBy(
            () ->
                small.publish(
                    project("pom.xml", "README.md"), "test-token", null, OPERATION, PRINCIPAL))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(github.writes).isZero();
  }

  @Test
  void initializingBranchIsRetriedWithinTheBoundAndRevokedAuthorizationRequestsConsent() {
    GitHub github = new GitHub(null);
    github.pendingRefs = 2;
    assertThat(github.publish(project("pom.xml")).fullName()).isEqualTo("ana/orders");
    github.userStatus = HttpStatus.UNAUTHORIZED;
    int writes = github.writes;
    assertThatThrownBy(() -> github.publish(project("pom.xml")))
        .isInstanceOf(ClientAuthorizationRequiredException.class);
    assertThat(github.writes).isEqualTo(writes);
  }

  @Test
  void signedMarkerCannotAuthorizePublicRepositoriesOrUnsafeRepositoryMetadata() {
    for (int variant = 0; variant < 3; variant++) {
      GitHub github = new GitHub(null);
      var project = project("pom.xml");
      github.description = github.identities.marker(project, OPERATION, PRINCIPAL, "ana/orders");
      if (variant == 0) github.privateRepository = false;
      if (variant == 1) github.branch = "../unsafe";
      if (variant == 2) github.htmlUrl = "http://github.test/ana/orders";
      assertConflict(() -> github.publish(project));
      assertThat(github.writes).isZero();
    }
  }

  @Test
  void rejectedConcurrentBranchUpdateIsNeverForcedAndCanBeReconciled() {
    GitHub github = new GitHub(null);
    github.rejectPatch = true;
    assertThatThrownBy(() -> github.publish(project("pom.xml")))
        .isInstanceOf(PublicationException.class);
    assertThat(github.head).isEqualTo(INITIAL);
    github.rejectPatch = false;
    assertThat(github.publish(project("pom.xml")).fullName()).isEqualTo("ana/orders");
    assertThat(github.creations).isEqualTo(1);
  }

  @Test
  void publicationAuditHasSubjectRepositoryOperationAndOutcomeWithoutCredentialsOrContent() {
    GitHub github = new GitHub(null);
    github.publish(project("sensitive-field.txt"));
    assertThat(github.events).hasSize(2);
    assertThat(github.events.getFirst().action()).isEqualTo("project.publish.requested");
    assertThat(github.events.getFirst().outcome()).isEqualTo(AuditEvent.Outcome.SUCCESS);
    assertThat(github.events.getFirst().operationOutcome())
        .isEqualTo(pe.axiz.core.commons.TransactionOutcome.UNKNOWN);
    AuditEvent event = github.events.getLast();
    assertThat(event.actor().subject()).matches("[0-9a-f]{64}");
    assertThat(event.resourceId()).isEqualTo("ana/orders");
    assertThat(event.correlationId()).isEqualTo(OPERATION.toString());
    assertThat(event.details()).containsEntry("phase", "CONFIRMED");
    assertThat(event.toMap().toString())
        .doesNotContain("test-token", PRINCIPAL, "sensitive-field.txt");
  }

  @Test
  void definiteWriteRejectionsStopRecoveryInsteadOfInvitingAnEndlessRetry() {
    for (HttpStatus status :
        List.of(
            HttpStatus.BAD_REQUEST,
            HttpStatus.FORBIDDEN,
            HttpStatus.NOT_FOUND,
            HttpStatus.CONFLICT,
            HttpStatus.UNPROCESSABLE_CONTENT)) {
      GitHub create = new GitHub(null);
      create.rejectCreate = status;
      assertConflict(() -> create.publish(project("pom.xml")));
      assertThat(create.creations).isZero();
      GitHub tree = new GitHub(null);
      tree.rejectTree = status;
      assertConflict(() -> tree.publish(project("pom.xml")));
      assertThat(tree.updates).isZero();
      GitHub branch = new GitHub(null);
      branch.rejectPatchStatus = status;
      assertConflict(() -> branch.publish(project("pom.xml")));
      assertThat(branch.updates).isZero();
    }
    GitHub patch = new GitHub(null);
    patch.rejectPatch = true;
    assertConflict(() -> patch.publish(project("pom.xml")));
  }

  @Test
  void revokedAuthorizationAndRateLimitsHaveDistinctOutcomesAtEveryMutationBoundary() {
    for (int boundary = 0; boundary < 3; boundary++) {
      for (String header : List.of("none", "Retry-After", "X-RateLimit-Remaining")) {
        GitHub github = new GitHub(null);
        boolean revoked = header.equals("none");
        HttpStatus status = revoked ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN;
        github.rateHeader = revoked ? null : header;
        if (boundary == 0) github.rejectCreate = status;
        else if (boundary == 1) github.rejectTree = status;
        else github.rejectPatchStatus = status;
        if (revoked) {
          assertThatThrownBy(() -> github.publish(project("pom.xml")))
              .isInstanceOf(ClientAuthorizationRequiredException.class);
        } else {
          assertThatThrownBy(() -> github.publish(project("pom.xml")))
              .isInstanceOfSatisfying(
                  PublicationException.class,
                  failure ->
                      assertThat(failure.phase())
                          .isEqualTo(PublicationException.Phase.CONTENT_PENDING));
        }
        assertThat(github.updates).isZero();
        if (boundary == 0) assertThat(github.creations).isZero();
      }
    }
    GitHub forbidden = new GitHub("restricted-org");
    forbidden.userStatus = HttpStatus.FORBIDDEN;
    assertConflict(() -> forbidden.publish(project("pom.xml")));
    assertThat(forbidden.writes).isZero();
    GitHub throttled = new GitHub(null);
    throttled.rejectCreate = HttpStatus.TOO_MANY_REQUESTS;
    assertThatThrownBy(() -> throttled.publish(project("pom.xml")))
        .isInstanceOfSatisfying(
            PublicationException.class,
            failure ->
                assertThat(failure.phase()).isEqualTo(PublicationException.Phase.CONTENT_PENDING));
  }

  @Test
  void invalidPublicationInputHasSafeFieldDiagnosticsAndNeverTouchesGitHub() {
    GitHub github = new GitHub(null);
    assertThatThrownBy(() -> github.publisher.validate(project("pom.xml"), "x".repeat(161)))
        .isInstanceOf(PublicationInputException.class);
    assertThatThrownBy(() -> github.publisher.validate(project(".git/config"), null))
        .isInstanceOf(PublicationInputException.class);
    assertThat(github.writes).isZero();
    github.userStatus = HttpStatus.SERVICE_UNAVAILABLE;
    assertThatThrownBy(() -> github.publish(project("pom.xml")))
        .isInstanceOf(PublicationException.class)
        .satisfies(
            failure ->
                assertThat(((PublicationException) failure).phase())
                    .isEqualTo(PublicationException.Phase.PREFLIGHT));
  }

  private static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action)
        .isInstanceOf(PublicationException.class)
        .satisfies(
            failure ->
                assertThat(((PublicationException) failure).phase())
                    .isEqualTo(PublicationException.Phase.CONFLICT));
  }

  /**
   * Fake con estado de la base de datos de Git: las fallas ocurren DESPUÉS del cambio de estado
   * solicitado.
   */
  private static final class GitHub {
    final JsonMapper json = JsonMapper.builder().build();
    final RestClient.Builder builder = RestClient.builder();
    final InitializrProperties properties;
    final PublicationIdentity identities =
        new PublicationIdentity(Base64.getEncoder().encodeToString(new byte[32]));
    final GitHubPublisher publisher;
    final String owner;
    final List<AuditEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
    String scopes = "repo, workflow";
    HttpStatus userStatus = HttpStatus.OK;
    boolean privateRepository = true;
    String branch = "main";
    String htmlUrl;
    int pendingRefs;
    boolean rejectPatch;
    HttpStatus rejectPatchStatus;
    String rateHeader;
    HttpStatus rejectCreate;
    HttpStatus rejectTree;
    String description;
    String head = INITIAL;
    String commitMessage;
    List<?> entries;
    LostResponse lose;
    int creations;
    int updates;
    int writes;

    GitHub(String organization) {
      owner = organization == null ? "ana" : organization;
      properties =
          new InitializrProperties(
              "2.0.0", URI.create("https://api.github.test"), organization, 100, "axiz-initializr");
      MockRestServiceServer.bindTo(builder)
          .build()
          .expect(
              manyTimes(),
              request -> {
                assertThat(request.getHeaders().getFirst("Authorization"))
                    .isEqualTo("Bearer test-token");
              })
          .andRespond(this::respond);
      publisher = new GitHubPublisher(builder, properties, identities, events::add);
    }

    GitHubPublisher.Repository publish(GeneratedProject project) {
      return publisher.publish(project, "test-token", null, OPERATION, PRINCIPAL);
    }

    ClientHttpResponse respond(ClientHttpRequest request) throws IOException {
      String path = request.getURI().getPath();
      HttpMethod method = request.getMethod();
      if (path.equals("/user") && userStatus != HttpStatus.OK)
        return withStatus(userStatus).createResponse(request);
      if (path.equals("/user") && method == HttpMethod.GET)
        return withSuccess("{\"login\":\"ana\"}", MediaType.APPLICATION_JSON)
            .header("X-OAuth-Scopes", scopes)
            .createResponse(request);
      if (path.equals("/repos/" + owner + "/orders") && method == HttpMethod.GET)
        return description == null
            ? withStatus(HttpStatus.NOT_FOUND).createResponse(request)
            : ok(request, repository());
      if (method != HttpMethod.GET) writes++;
      if (path.equals("/user/repos") || path.equals("/orgs/" + owner + "/repos")) {
        if (rejectCreate != null) return rejected(request, rejectCreate);
        Map<?, ?> body = body(request);
        assertThat(body.get("private")).isEqualTo(true);
        assertThat(body.get("auto_init")).isEqualTo(true);
        description = (String) body.get("description");
        creations++;
        return written(request, LostResponse.REPOSITORY, repository());
      }
      if (path.endsWith("/git/ref/heads/main") && pendingRefs-- > 0)
        return withStatus(HttpStatus.CONFLICT).createResponse(request);
      if (path.endsWith("/git/ref/heads/main"))
        return ok(request, Map.of("object", Map.of("sha", head)));
      if (path.endsWith("/git/commits/" + INITIAL))
        return ok(
            request,
            Map.of(
                "tree",
                Map.of("sha", BASE_TREE),
                "parents",
                List.of(),
                "message",
                "Initial commit"));
      if (path.endsWith("/git/commits/" + COMMIT))
        return ok(
            request,
            Map.of(
                "tree",
                Map.of("sha", TREE),
                "parents",
                List.of(Map.of("sha", INITIAL)),
                "message",
                commitMessage));
      if (path.endsWith("/git/trees")) {
        if (rejectTree != null) return rejected(request, rejectTree);
        Map<?, ?> body = body(request);
        assertThat(body.get("base_tree")).isEqualTo(BASE_TREE);
        entries = (List<?>) body.get("tree");
        return written(request, LostResponse.TREE, Map.of("sha", TREE));
      }
      if (path.endsWith("/git/commits")) {
        Map<?, ?> body = body(request);
        assertThat(body.get("parents")).isEqualTo(List.of(INITIAL));
        commitMessage = (String) body.get("message");
        return written(request, LostResponse.COMMIT, Map.of("sha", COMMIT));
      }
      if (path.endsWith("/git/refs/heads/main")) {
        assertThat(method).isEqualTo(HttpMethod.PATCH);
        Map<?, ?> body = body(request);
        assertThat(body.get("force")).isEqualTo(false);
        if (rejectPatchStatus != null) return rejected(request, rejectPatchStatus);
        if (rejectPatch)
          return withStatus(HttpStatus.UNPROCESSABLE_CONTENT).createResponse(request);
        head = (String) body.get("sha");
        updates++;
        return written(request, LostResponse.REF, Map.of("object", Map.of("sha", head)));
      }
      throw new AssertionError("Unexpected GitHub request: " + method + " " + path);
    }

    ClientHttpResponse rejected(ClientHttpRequest request, HttpStatus status) throws IOException {
      var response = withStatus(status);
      if (rateHeader != null)
        response.header(rateHeader, rateHeader.equals("Retry-After") ? "60" : "0");
      return response.createResponse(request);
    }

    Map<?, ?> body(ClientHttpRequest request) {
      return json.readValue(((MockClientHttpRequest) request).getBodyAsString(), Map.class);
    }

    Map<String, Object> repository() {
      return Map.of(
          "full_name",
          owner + "/orders",
          "private",
          privateRepository,
          "description",
          description,
          "default_branch",
          branch,
          "html_url",
          htmlUrl == null ? "https://github.test/" + owner + "/orders" : htmlUrl);
    }

    ClientHttpResponse written(ClientHttpRequest request, LostResponse after, Map<?, ?> result)
        throws IOException {
      if (lose == after) {
        lose = null;
        return withStatus(HttpStatus.SERVICE_UNAVAILABLE).createResponse(request);
      }
      return ok(request, result);
    }

    ClientHttpResponse ok(ClientHttpRequest request, Map<?, ?> body) throws IOException {
      return withSuccess(json.writeValueAsString(body), MediaType.APPLICATION_JSON)
          .createResponse(request);
    }
  }
}
