package pe.axiz.initializr.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import pe.axiz.codegen.output.GeneratedProject;
import pe.axiz.initializr.config.InitializrProperties;

/**
 * Creates a private repository with the generated project in a single commit (Git Data API), acting
 * with the OAuth token of the signed-in user: the initializr never stores tokens.
 */
@Service
public class GitHubPublisher {

  /**
   * Created repository.
   *
   * @param fullName owner/name
   * @param url web URL
   */
  public record Repository(String fullName, String url) {}

  private final RestClient.Builder clients;
  private final InitializrProperties properties;

  public GitHubPublisher(RestClient.Builder clients, InitializrProperties properties) {
    this.clients = clients;
    this.properties = properties;
  }

  /** Creates the repository and pushes the project to its default branch. */
  public Repository publish(GeneratedProject project, String token, @Nullable String description) {
    if (project.files().size() > properties.maxFiles()) {
      throw new IllegalArgumentException(
          "Project has more than " + properties.maxFiles() + " files");
    }
    RestClient github =
        clients
            .clone()
            .baseUrl(properties.githubApi().toString())
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
            .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
            .build();
    Map<String, Object> create = new LinkedHashMap<>();
    create.put("name", project.rootName());
    create.put("description", description == null ? "" : description);
    create.put("private", true);
    create.put("auto_init", true);
    String org = properties.githubOrganization();
    @Nullable Map<?, ?> created =
        github
            .post()
            .uri(
                org == null || org.isBlank() ? "/user/repos" : "/orgs/{org}/repos",
                org == null ? "" : org)
            .contentType(MediaType.APPLICATION_JSON)
            .body(create)
            .retrieve()
            .body(Map.class);
    Map<?, ?> repo = required(created, "repository");
    String fullName = text(repo, "full_name");
    String branch = repo.get("default_branch") == null ? "main" : text(repo, "default_branch");

    Map<?, ?> ref =
        required(
            github
                .get()
                .uri("/repos/" + fullName + "/git/ref/heads/{branch}", branch)
                .retrieve()
                .body(Map.class),
            "ref");
    String parent = text((Map<?, ?>) ref.get("object"), "sha");
    Map<?, ?> parentCommit =
        required(
            github
                .get()
                .uri("/repos/" + fullName + "/git/commits/{sha}", parent)
                .retrieve()
                .body(Map.class),
            "commit");
    String baseTree = text((Map<?, ?>) parentCommit.get("tree"), "sha");

    Map<?, ?> tree =
        github
            .post()
            .uri("/repos/" + fullName + "/git/trees")
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("base_tree", baseTree, "tree", tree(project)))
            .retrieve()
            .body(Map.class);
    Map<?, ?> commit =
        github
            .post()
            .uri("/repos/" + fullName + "/git/commits")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                Map.of(
                    "message",
                    // a Conventional Commit feat: from 0.0.0 (nothing released) release-please
                    // proposes 0.1.0 as the first release, the version the pom already targets
                    "feat: generate " + project.rootName() + " with axiz initializr",
                    "tree",
                    text(tree, "sha"),
                    "parents",
                    List.of(parent)))
            .retrieve()
            .body(Map.class);
    github
        .patch()
        .uri("/repos/" + fullName + "/git/refs/heads/{branch}", branch)
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("sha", text(commit, "sha"), "force", false))
        .retrieve()
        .toBodilessEntity();
    return new Repository(fullName, text(repo, "html_url"));
  }

  static List<Map<String, Object>> tree(GeneratedProject project) {
    List<Map<String, Object>> entries = new ArrayList<>();
    project
        .files()
        .forEach(
            (path, content) -> {
              Map<String, Object> e = new LinkedHashMap<>();
              e.put("path", path);
              e.put("mode", path.endsWith(".sh") || path.equals("mvnw") ? "100755" : "100644");
              e.put("type", "blob");
              e.put("content", new String(content, StandardCharsets.UTF_8));
              entries.add(e);
            });
    return entries;
  }

  private static Map<?, ?> required(@Nullable Map<?, ?> body, String what) {
    if (body == null) {
      throw new IllegalStateException("GitHub returned no " + what);
    }
    return body;
  }

  private static String text(@Nullable Map<?, ?> map, String key) {
    Object v = map == null ? null : map.get(key);
    if (v == null) {
      throw new IllegalStateException("GitHub response without " + key);
    }
    return String.valueOf(v);
  }
}
