package pe.axiz.adapter.observability.aws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.axiz.adapter.aws.identity.AwsCredentialSource;
import pe.axiz.adapter.aws.transport.AsyncAwsClients;
import pe.axiz.adapter.aws.transport.AsyncAwsTransportSettings;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

class AwsObservabilityTest {
  @Test
  void synchronousInstrumentationRetainsOverridesAndDoesNotDuplicateAnExistingObserver() {
    var interceptor = new ExecutionInterceptor() {};
    var observer = new ObservationExecutionInterceptor(ObservationRegistry.NOOP);
    try (var client =
        AwsObservability.build(
            DynamoDbClient.builder()
                .credentialsProvider(
                    StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .overrideConfiguration(
                    c -> c.addExecutionInterceptor(interceptor).addExecutionInterceptor(observer)),
            ObservationRegistry.NOOP,
            "us-east-1",
            null,
            Duration.ofSeconds(7))) {
      var overrides = client.serviceClientConfiguration().overrideConfiguration();
      assertEquals(Duration.ofSeconds(7), overrides.apiCallTimeout().orElseThrow());
      assertTrue(overrides.executionInterceptors().contains(interceptor));
      assertEquals(
          1,
          overrides.executionInterceptors().stream()
              .filter(ObservationExecutionInterceptor.class::isInstance)
              .count());
    }
  }

  @Test
  void nativeInstrumentationRetainsCustomInterceptorsAndAddsExactlyOneObserver() {
    var interceptor = new ExecutionInterceptor() {};
    try (var owner =
            new AsyncAwsClients(
                AwsCredentialSource.borrowed(() -> AwsBasicCredentials.create("test", "test")),
                Clock.systemUTC(),
                Duration.ofDays(1),
                AsyncAwsTransportSettings.defaults());
        var client =
            AwsObservability.configured(
                owner,
                DynamoDbAsyncClient.builder()
                    .overrideConfiguration(c -> c.addExecutionInterceptor(interceptor)),
                ObservationRegistry.NOOP,
                Map.of("axiz.aws.region", "us-east-1")::get,
                "dynamodb")) {
      var overrides = client.serviceClientConfiguration().overrideConfiguration();
      assertTrue(overrides.executionInterceptors().contains(interceptor));
      assertEquals(
          1,
          overrides.executionInterceptors().stream()
              .filter(ObservationExecutionInterceptor.class::isInstance)
              .count());
    }
  }
}
