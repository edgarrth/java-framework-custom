package pe.axiz.adapter.observability.aws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

class AwsClientsTest {

  private static DynamoDbClient dynamo(Map<String, String> properties) {
    // no call is made: the transport is a placeholder so the test needs no HTTP implementation
    return AwsClients.configured(
        DynamoDbClient.builder().httpClient(new NoHttp()),
        ObservationRegistry.NOOP,
        properties::get,
        "dynamodb");
  }

  private static final class NoHttp implements SdkHttpClient {
    @Override
    public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
      throw new UnsupportedOperationException("no calls in this test");
    }

    @Override
    public void close() {}
  }

  @Test
  void theSharedEndpointAppliesToEveryService() {
    try (DynamoDbClient client =
        dynamo(
            Map.of("axiz.aws.region", "us-east-1", "axiz.aws.endpoint", "http://localhost:4566"))) {
      assertEquals(
          URI.create("http://localhost:4566"),
          client.serviceClientConfiguration().endpointOverride().orElseThrow());
      assertEquals(Region.US_EAST_1, client.serviceClientConfiguration().region());
    }
  }

  @Test
  void theServiceEndpointWins() {
    try (DynamoDbClient client =
        dynamo(
            Map.of(
                "axiz.aws.region", "us-east-1",
                "axiz.aws.endpoint", "http://localhost:4566",
                "axiz.aws.dynamodb.endpoint", "http://localhost:9324"))) {
      assertEquals(
          URI.create("http://localhost:9324"),
          client.serviceClientConfiguration().endpointOverride().orElseThrow());
    }
  }

  @Test
  void withoutEndpointTheClientGoesToAws() {
    try (DynamoDbClient client = dynamo(Map.of("axiz.aws.region", "us-east-1"))) {
      assertFalse(client.serviceClientConfiguration().endpointOverride().isPresent());
    }
  }

  @Test
  void blankValuesAreIgnoredAndTheSharedEndpointIsTheFallback() {
    Map<String, String> properties =
        Map.of(
            "axiz.aws.region", " ",
            "axiz.aws.dynamodb.endpoint", " ",
            "axiz.aws.endpoint", "http://localhost:4566");
    // a blank region leaves the one of the builder (or the SDK chain) in place
    try (DynamoDbClient client =
        AwsClients.configured(
            DynamoDbClient.builder().httpClient(new NoHttp()).region(Region.SA_EAST_1),
            ObservationRegistry.NOOP,
            properties::get,
            "dynamodb")) {
      assertEquals(Region.SA_EAST_1, client.serviceClientConfiguration().region());
      assertEquals(
          URI.create("http://localhost:4566"),
          client.serviceClientConfiguration().endpointOverride().orElseThrow());
    }
  }

  @Test
  void buildWithoutRegionOrEndpointLeavesThemToTheSdk() {
    try (DynamoDbClient client =
        AwsClients.build(
            DynamoDbClient.builder().httpClient(new NoHttp()).region(Region.EU_WEST_1),
            ObservationRegistry.NOOP,
            null,
            null)) {
      assertEquals(Region.EU_WEST_1, client.serviceClientConfiguration().region());
      assertFalse(client.serviceClientConfiguration().endpointOverride().isPresent());
    }
  }

  @Test
  void aClientWithoutAnyRegionNamesTheSettingToFix() {
    Assumptions.assumeTrue(
        System.getenv("AWS_REGION") == null && System.getenv("AWS_DEFAULT_REGION") == null,
        "the environment of this run defines a region");
    String config = System.getProperty("aws.configFile");
    String imds = System.getProperty("aws.disableEc2Metadata");
    System.setProperty("aws.configFile", "/nonexistent/axiz-test-config");
    System.setProperty("aws.disableEc2Metadata", "true");
    try {
      IllegalStateException e =
          assertThrows(
              IllegalStateException.class,
              () ->
                  AwsClients.configured(
                      DynamoDbClient.builder().httpClient(new NoHttp()),
                      ObservationRegistry.NOOP,
                      key -> null,
                      "dynamodb"));
      assertTrue(e.getMessage().contains("axiz.aws.region"), e.getMessage());
    } finally {
      restore("aws.configFile", config);
      restore("aws.disableEc2Metadata", imds);
    }
  }

  private static void restore(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }
}
