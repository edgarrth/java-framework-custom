package pe.axiz.initializr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import pe.axiz.codegen.output.GeneratedProject;
import pe.axiz.initializr.config.InitializrProperties;

class GitHubPublisherTest {

  @Test
  void createsTheRepositoryInOneCommit() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    InitializrProperties props =
        new InitializrProperties(
            "1.0.0", URI.create("https://api.github.test"), "acme", 100, "axiz-initializr");
    GeneratedProject project =
        new GeneratedProject(
            "orders-business",
            Map.of(
                "pom.xml",
                "<project/>".getBytes(StandardCharsets.UTF_8),
                "local/localstack/init.sh",
                "#!/bin/bash".getBytes(StandardCharsets.UTF_8)));

    server
        .expect(requestTo("https://api.github.test/orgs/acme/repos"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer user-token"))
        .andExpect(jsonPath("$.private").value(true))
        .andRespond(
            withSuccess(
                "{\"full_name\":\"acme/orders-business\",\"html_url\":\"https://github.test/acme/orders-business\",\"default_branch\":\"main\"}",
                MediaType.APPLICATION_JSON));
    server
        .expect(requestTo("https://api.github.test/repos/acme/orders-business/git/ref/heads/main"))
        .andRespond(withSuccess("{\"object\":{\"sha\":\"c0\"}}", MediaType.APPLICATION_JSON));
    server
        .expect(requestTo("https://api.github.test/repos/acme/orders-business/git/commits/c0"))
        .andRespond(withSuccess("{\"tree\":{\"sha\":\"t0\"}}", MediaType.APPLICATION_JSON));
    server
        .expect(requestTo("https://api.github.test/repos/acme/orders-business/git/trees"))
        .andExpect(jsonPath("$.base_tree").value("t0"))
        .andExpect(jsonPath("$.tree[?(@.path == 'local/localstack/init.sh')].mode").value("100755"))
        .andRespond(withSuccess("{\"sha\":\"t1\"}", MediaType.APPLICATION_JSON));
    server
        .expect(requestTo("https://api.github.test/repos/acme/orders-business/git/commits"))
        .andExpect(jsonPath("$.parents[0]").value("c0"))
        .andRespond(withSuccess("{\"sha\":\"c1\"}", MediaType.APPLICATION_JSON));
    server
        .expect(requestTo("https://api.github.test/repos/acme/orders-business/git/refs/heads/main"))
        .andExpect(method(HttpMethod.PATCH))
        .andExpect(jsonPath("$.sha").value("c1"))
        .andRespond(withSuccess());

    GitHubPublisher.Repository repo =
        new GitHubPublisher(builder, props).publish(project, "user-token", "Orders");
    assertThat(repo.url()).isEqualTo("https://github.test/acme/orders-business");
    server.verify();
  }
}
