package pe.axiz.test.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AwsIdentityFixtureTest {
  @Test
  void fixtureTracksIdentityRequestsAndDetectsMissingTemporaryCredentials() throws Exception {
    for (boolean accepted : new boolean[] {true, false}) {
      Path token;
      try (var fixture = new AwsIdentityFixture(accepted);
          var client = HttpClient.newHttpClient()) {
        token = Path.of(fixture.environment().get("AWS_WEB_IDENTITY_TOKEN_FILE"));
        assertTrue(Files.exists(token));
        assertFalse(fixture.environment().containsKey("AWS_ACCESS_KEY_ID"));
        var request =
            HttpRequest.newBuilder(URI.create(fixture.endpoint()))
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "Action=AssumeRoleWithWebIdentity&WebIdentityToken=fixture-token"))
                .build();
        assertEquals(
            accepted ? 200 : 400,
            client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(1, fixture.stsRequests());
        assertEquals(0, fixture.invalidRequests());
        var ssm =
            HttpRequest.newBuilder(URI.create(fixture.endpoint()))
                .header("X-Amz-Target", "AmazonSSM.GetParametersByPath")
                .header("Authorization", "Credential=ASIAFIXTURE")
                .header("X-Amz-Security-Token", "fixture-session")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        assertTrue(
            client.send(ssm, HttpResponse.BodyHandlers.ofString()).body().contains("loaded"));
        assertEquals(1, fixture.ssmRequests());
        var metadata =
            HttpRequest.newBuilder(URI.create(fixture.endpoint() + "/imds")).GET().build();
        assertEquals(500, client.send(metadata, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(1, fixture.imdsRequests());
        client.send(
            HttpRequest.newBuilder(URI.create(fixture.endpoint()))
                .POST(HttpRequest.BodyPublishers.ofString("invalid"))
                .build(),
            HttpResponse.BodyHandlers.discarding());
        client.send(
            HttpRequest.newBuilder(URI.create(fixture.endpoint()))
                .header("X-Amz-Target", "AmazonSSM.GetParametersByPath")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(),
            HttpResponse.BodyHandlers.discarding());
        assertEquals(2, fixture.invalidRequests());
      }
      assertFalse(Files.exists(token));
    }
  }
}
