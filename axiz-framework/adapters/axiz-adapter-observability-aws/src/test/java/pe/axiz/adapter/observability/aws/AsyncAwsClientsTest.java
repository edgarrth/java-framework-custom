package pe.axiz.adapter.observability.aws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import pe.axiz.adapter.aws.identity.AwsCredentialSource;
import pe.axiz.adapter.aws.transport.AsyncAwsClients;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.core.client.config.SdkAdvancedClientOption;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.utils.SdkAutoCloseable;

class AsyncAwsClientsTest {

  static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");
  static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  static final class Source implements AwsCredentialsProvider, SdkAutoCloseable {
    AwsCredentials next = credentials("initial", NOW.plusSeconds(100));
    final AtomicInteger calls = new AtomicInteger();
    int closes;
    boolean down;
    boolean failClose;

    @Override
    public AwsCredentials resolveCredentials() {
      calls.incrementAndGet();
      if (down) throw new IllegalStateException("credential endpoint unavailable");
      return next;
    }

    AwsCredentialSource resource() {
      return AwsCredentialSource.owned(this, this::close);
    }

    @Override
    public void close() {
      closes++;
      if (failClose) throw new IllegalStateException("close failed");
    }
  }

  static AwsCredentials credentials(String id, Instant expiration) {
    return AwsSessionCredentials.builder()
        .accessKeyId(id)
        .secretAccessKey("fixture-secret")
        .sessionToken("fixture-session")
        .expirationTime(expiration)
        .build();
  }

  @Test
  void explicitTransportRegionEndpointsAndTimeoutsAreOwnedByFramework() {
    Source source = new Source();
    try (var clients = new AsyncAwsClients(source.resource(), CLOCK, Duration.ofDays(1))) {
      try (var client =
          AwsObservability.configured(
              clients,
              DynamoDbAsyncClient.builder(),
              ObservationRegistry.NOOP,
              Map.of(
                      "axiz.aws.region",
                      "us-east-1",
                      "axiz.aws.endpoint",
                      "http://localhost:4566",
                      "axiz.aws.dynamodb.endpoint",
                      "http://localhost:9324")
                  ::get,
              "dynamodb")) {
        assertEquals(Region.US_EAST_1, client.serviceClientConfiguration().region());
        assertEquals(
            URI.create("http://localhost:9324"),
            client.serviceClientConfiguration().endpointOverride().orElseThrow());
        var settings = client.serviceClientConfiguration().overrideConfiguration();
        assertEquals(Duration.ofSeconds(60), settings.apiCallTimeout().orElseThrow());
        assertEquals(Duration.ofSeconds(30), settings.apiCallAttemptTimeout().orElseThrow());
        assertTrue(
            settings.advancedOption(SdkAdvancedClientOption.SIGNER).orElseThrow()
                instanceof Aws4Signer,
            "finite JSON requests must avoid the SDK's contended shared digest cache");
        assertFalse(settings.executionInterceptors().isEmpty());
      }
      try (var client =
          AwsObservability.configured(
              clients,
              DynamoDbAsyncClient.builder(),
              ObservationRegistry.NOOP,
              Map.of(
                      "axiz.aws.region",
                      "us-east-1",
                      "axiz.aws.dynamodb.endpoint",
                      " ",
                      "axiz.aws.endpoint",
                      "http://localhost:4566")
                  ::get,
              "dynamodb")) {
        assertEquals(
            URI.create("http://localhost:4566"),
            client.serviceClientConfiguration().endpointOverride().orElseThrow());
      }
      try (var client =
          AwsObservability.configured(
              clients,
              DynamoDbAsyncClient.builder(),
              ObservationRegistry.NOOP,
              Map.of("axiz.aws.region", "us-east-1")::get,
              "dynamodb")) {
        assertTrue(client.serviceClientConfiguration().endpointOverride().isEmpty());
      }
      assertEquals(
          1, source.calls.get(), "building several clients does not resolve credentials again");
    }
    assertEquals(1, source.closes);
  }

  @Test
  void missingExplicitRegionFailsRatherThanStartingInstanceMetadataLookup() {
    Assumptions.assumeTrue(System.getenv("AWS_REGION") == null);
    try (var clients = new AsyncAwsClients(new Source().resource(), CLOCK, Duration.ofDays(1))) {
      assertThrows(
          IllegalStateException.class,
          () ->
              AwsObservability.configured(
                  clients,
                  DynamoDbAsyncClient.builder(),
                  ObservationRegistry.NOOP,
                  key -> "",
                  "dynamodb"));
    }
  }
}
