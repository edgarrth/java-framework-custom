package pe.axiz.quality.containers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.images.builder.Transferable;

class PatchedKeycloakIT {
  @Test
  @Timeout(180)
  void importedRealmIssuesSignedTokensAndRejectsWrongCredentialsAsNonRoot() throws Exception {
    boolean available = DockerClientFactory.instance().isDockerAvailable();
    if (Boolean.getBoolean("axiz.it.requireDocker")) assertTrue(available, "Docker is required");
    Assumptions.assumeTrue(available);
    String realm =
        """
        {"realm":"security-proof","enabled":true,
         "clients":[{"clientId":"probe","enabled":true,"publicClient":false,
                     "secret":"synthetic-local-only","serviceAccountsEnabled":true}]}
        """;
    try (var server =
            TestContainerImages.keycloak()
                .withCopyToContainer(
                    Transferable.of(realm, 0444), "/opt/keycloak/data/import/proof.json")
                .withCommand("start-dev", "--import-realm");
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
      server.start();
      assertEquals("1000", server.execInContainer("id", "-u").getStdout().strip());
      var digest =
          server.execInContainer(
              "sha256sum", "/opt/keycloak/lib/lib/main/com.google.guava.guava-33.6.0-jre.jar");
      assertEquals(0, digest.getExitCode());
      assertTrue(
          digest
              .getStdout()
              .startsWith("b530942257fb935f8b2cfaa5f8eb5bd59c555fd8e8d01b8ce98912e077ea606c"));
      String issuer =
          "http://"
              + server.getHost()
              + ":"
              + server.getMappedPort(8080)
              + "/realms/security-proof";
      var response = token(client, issuer, "synthetic-local-only");
      assertEquals(200, response.statusCode());
      var object = JSONObjectUtils.parse(response.body());
      var jwt = SignedJWT.parse(JSONObjectUtils.getString(object, "access_token"));
      var keys =
          client.send(
              HttpRequest.newBuilder(URI.create(issuer + "/protocol/openid-connect/certs"))
                  .timeout(Duration.ofSeconds(10))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, keys.statusCode());
      var key = JWKSet.parse(keys.body()).getKeyByKeyId(jwt.getHeader().getKeyID()).toRSAKey();
      assertTrue(jwt.verify(new RSASSAVerifier(key.toRSAPublicKey())));
      assertEquals(issuer, jwt.getJWTClaimsSet().getIssuer());
      assertEquals("probe", jwt.getJWTClaimsSet().getStringClaim("azp"));
      assertFalse(jwt.getJWTClaimsSet().getSubject().isBlank());
      assertTrue(jwt.getJWTClaimsSet().getExpirationTime().toInstant().isAfter(Instant.now()));
      assertEquals(401, token(client, issuer, "wrong-secret").statusCode());
    }
  }

  private static HttpResponse<String> token(HttpClient client, String issuer, String secret)
      throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(issuer + "/protocol/openid-connect/token"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "grant_type=client_credentials&client_id=probe&client_secret=" + secret))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
