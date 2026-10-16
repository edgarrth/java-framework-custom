package pe.axiz.core.observability.awssdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.util.Map;
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
}
