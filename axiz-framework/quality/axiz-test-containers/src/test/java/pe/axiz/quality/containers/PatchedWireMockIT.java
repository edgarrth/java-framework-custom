package pe.axiz.quality.containers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.client.WireMock;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.DockerClientFactory;

class PatchedWireMockIT {
  @Test
  @Timeout(120)
  void nonRootServerRegistersStubsAndRendersSoapResponses() throws Exception {
    boolean available = DockerClientFactory.instance().isDockerAvailable();
    if (Boolean.getBoolean("axiz.it.requireDocker")) assertTrue(available, "Docker is required");
    Assumptions.assumeTrue(available);
    try (var server = TestContainerImages.wiremock().withCommand("--global-response-templating");
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
      server.start();
      assertEquals("1000", server.execInContainer("id", "-u").getStdout().strip());
      String base = "http://" + server.getHost() + ":" + server.getMappedPort(8080);
      var admin = new WireMock(server.getHost(), server.getMappedPort(8080));
      admin.register(
          WireMock.post("/soap/credit")
              .withHeader("SOAPAction", WireMock.equalTo("urn:credit"))
              .withRequestBody(WireMock.containing("CreditRequest"))
              .willReturn(
                  WireMock.aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "text/xml")
                      .withBody("<CreditResponse>{{request.headers.X-Trace}}</CreditResponse>")));
      var response =
          client.send(
              HttpRequest.newBuilder(URI.create(base + "/soap/credit"))
                  .header("SOAPAction", "urn:credit")
                  .header("X-Trace", "synthetic-proof")
                  .POST(HttpRequest.BodyPublishers.ofString("<CreditRequest/>"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals("<CreditResponse>synthetic-proof</CreditResponse>", response.body());
      assertTrue(
          response.headers().firstValue("Content-Type").orElseThrow().startsWith("text/xml"));
      var unmatched =
          client.send(
              HttpRequest.newBuilder(URI.create(base + "/unmatched")).build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(404, unmatched.statusCode());
    }
  }
}
