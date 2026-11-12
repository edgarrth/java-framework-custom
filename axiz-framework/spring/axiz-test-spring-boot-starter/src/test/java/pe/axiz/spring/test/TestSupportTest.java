package pe.axiz.spring.test;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.localstack.LocalStackContainer;
import pe.axiz.core.commons.RequestContext;

class TestSupportTest {

  @Test
  void ephemeralKeysAreIndependent256BitValuesAndRegisterTheirDirectory() throws Exception {
    Map<String, Supplier<Object>> properties = new HashMap<>();
    EphemeralSecrets.register(properties::put, "field-key", "index-key");
    Path directory = Path.of(properties.get("axiz.secrets.directory").get().toString());
    try {
      String field = Files.readString(directory.resolve("field-key"));
      String index = Files.readString(directory.resolve("index-key"));
      assertEquals(32, Base64.getDecoder().decode(field).length);
      assertEquals(32, Base64.getDecoder().decode(index).length);
      assertNotEquals(field, index);
      assertThrows(IllegalArgumentException.class, () -> EphemeralSecrets.keys("../escape"));
    } finally {
      Files.delete(directory.resolve("field-key"));
      Files.delete(directory.resolve("index-key"));
      Files.delete(directory);
    }
  }

  @Test
  void httpStubRegistersItsReachableDynamicEndpoint() throws Exception {
    Map<String, Supplier<Object>> properties = new HashMap<>();
    var server = HttpStubs.start("ledger", properties::put);
    try (var client = HttpClient.newHttpClient()) {
      server.stubFor(get("/balance").willReturn(ok("42")));
      String endpoint = properties.get("axiz.http-client.clients.ledger.base-url").get().toString();
      var response =
          client.send(
              HttpRequest.newBuilder(URI.create(endpoint + "/balance")).build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals("42", response.body());
    } finally {
      server.stop();
    }
    assertFalse(server.isRunning());
  }

  @Test
  void requestContextIsScopedAndRestored() {
    var previous = RequestContext.current();
    try (var ignored = RequestContexts.open("channel-app", "customer-1", "mobile")) {
      var metadata = RequestContext.current().orElseThrow();
      assertEquals("channel-app", metadata.clientId());
      assertEquals("customer-1", metadata.subject());
      assertEquals("mobile", metadata.channel());
    }
    assertEquals(previous, RequestContext.current());
  }

  @Test
  void containerConfigurationsExposeUsableDefaultsWithoutStartingInfrastructure() {
    try (var postgres = new PostgresTestConfiguration().axizPostgresContainer();
        var redis = new RedisTestConfiguration().axizRedisContainer();
        var kafka = new KafkaTestConfiguration().axizKafkaContainer();
        var localstack = new LocalStackTestConfiguration().axizLocalStackContainer()) {
      assertEquals("app", postgres.getDatabaseName());
      assertEquals("app", postgres.getUsername());
      assertFalse(postgres.getPassword().isEmpty());
      assertEquals(List.of(6379), redis.getExposedPorts());
      assertFalse(kafka.isRunning());
      assertFalse(localstack.isRunning());
    }
  }

  @Test
  void kafkaPropertiesUseTheContainerEndpointWithoutIam() {
    var kafka = mock(KafkaContainer.class);
    when(kafka.getBootstrapServers()).thenReturn("localhost:32123");
    Map<String, Supplier<Object>> properties = new HashMap<>();
    new KafkaTestConfiguration().axizKafkaProperties(kafka).accept(properties::put);
    assertEquals("localhost:32123", properties.get("axiz.messaging.kafka.bootstrap-servers").get());
    assertEquals("false", properties.get("axiz.messaging.kafka.iam").get());
  }

  @Test
  void awsPropertiesPointBothClientStacksAtTheSameTestBackend() {
    var localstack = mock(LocalStackContainer.class);
    when(localstack.getEndpoint()).thenReturn(URI.create("http://localhost:32124"));
    when(localstack.getRegion()).thenReturn("us-east-1");
    when(localstack.getAccessKey()).thenReturn("test-access");
    when(localstack.getSecretKey()).thenReturn("test-secret");
    String access = System.getProperty("aws.accessKeyId");
    String secret = System.getProperty("aws.secretAccessKey");
    Map<String, Supplier<Object>> properties = new HashMap<>();
    new LocalStackTestConfiguration().axizLocalStackProperties(localstack).accept(properties::put);
    assertEquals("http://localhost:32124", properties.get("axiz.aws.endpoint").get());
    assertEquals(
        properties.get("axiz.aws.endpoint").get(),
        properties.get("spring.cloud.aws.endpoint").get());
    assertEquals("us-east-1", properties.get("axiz.aws.region").get());
    assertEquals("us-east-1", properties.get("spring.cloud.aws.region.static").get());
    assertEquals("test-access", properties.get("spring.cloud.aws.credentials.access-key").get());
    assertEquals("test-secret", properties.get("spring.cloud.aws.credentials.secret-key").get());
    var credentials =
        new LocalStackTestConfiguration()
            .axizLocalStackCredentialsProvider(localstack)
            .resolveCredentials();
    assertEquals("test-access", credentials.accessKeyId());
    assertEquals("test-secret", credentials.secretAccessKey());
    assertEquals(access, System.getProperty("aws.accessKeyId"));
    assertEquals(secret, System.getProperty("aws.secretAccessKey"));
    assertTrue(properties.values().stream().allMatch(value -> value.get() != null));
  }
}
