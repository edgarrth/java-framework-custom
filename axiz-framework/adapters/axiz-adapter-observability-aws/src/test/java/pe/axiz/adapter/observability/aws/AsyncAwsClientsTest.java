package pe.axiz.adapter.observability.aws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.utils.SdkAutoCloseable;

class AsyncAwsClientsTest {
  @Test
  void connectionLifetimesAreExplicitPositiveAndSupportStandardDurationNotations() {
    assertEquals(
        Duration.ofMinutes(5), AsyncAwsClients.duration(key -> null, "ttl", Duration.ofMinutes(5)));
    for (var entry :
        Map.of(
                "PT0.1S",
                Duration.ofMillis(100),
                "100ms",
                Duration.ofMillis(100),
                "2s",
                Duration.ofSeconds(2),
                "3m",
                Duration.ofMinutes(3),
                "1h",
                Duration.ofHours(1))
            .entrySet()) {
      assertEquals(
          entry.getValue(),
          AsyncAwsClients.duration(key -> entry.getKey(), "ttl", Duration.ofMinutes(5)));
    }
    for (String value :
        new String[] {
          "0ms", "PT0S", "-1s", "PT0.000000001S", "not-a-duration", "999999999999999999999h"
        }) {
      assertThrows(
          IllegalArgumentException.class,
          () -> AsyncAwsClients.duration(key -> value, "ttl", Duration.ofMinutes(5)));
    }
  }

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
  void requestThreadsOnlyReadSnapshotAndBackgroundRefreshFailureDoesNotCallSourceAgain() {
    Source source = new Source();
    try (var warmed = new WarmedAwsCredentials(source, CLOCK, Duration.ofDays(1))) {
      assertEquals(1, source.calls.get());
      for (int i = 0; i < 100; i++) assertSame(source.next, warmed.resolveCredentials());
      assertEquals(1, source.calls.get());
      source.next = credentials("refreshed", NOW.plusSeconds(200));
      CompletableFuture.runAsync(warmed::refresh).join();
      assertSame(source.next, warmed.resolveCredentials());
      source.down = true;
      warmed.refresh();
      assertEquals("refreshed", warmed.resolveCredentials().accessKeyId());
      assertEquals(3, source.calls.get());
    }
    assertEquals(1, source.closes);
  }

  @Test
  void nearExpirationFailsClosedAndInitialFailureClosesOwnedSource() {
    Source source = new Source();
    source.next = credentials("expired", NOW.plusSeconds(10));
    try (var warmed = new WarmedAwsCredentials(source, CLOCK, Duration.ofDays(1))) {
      assertThrows(SdkClientException.class, warmed::resolveCredentials);
      source.next = credentials("new", NOW.plusSeconds(60));
      warmed.refresh();
      assertEquals("new", warmed.resolveCredentials().accessKeyId());
    }
    Source down = new Source();
    down.down = true;
    down.failClose = true;
    var failure =
        assertThrows(
            IllegalStateException.class,
            () -> new WarmedAwsCredentials(down, CLOCK, Duration.ofDays(1)));
    assertEquals(1, down.closes);
    assertEquals(1, failure.getSuppressed().length);
    assertThrows(
        IllegalArgumentException.class,
        () -> new WarmedAwsCredentials(new Source(), CLOCK, Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () -> new WarmedAwsCredentials(new Source(), CLOCK, Duration.ofNanos(1)));
    AwsCredentialsProvider unavailable =
        () -> {
          throw new IllegalStateException("offline");
        };
    assertThrows(
        IllegalStateException.class,
        () -> new WarmedAwsCredentials(unavailable, CLOCK, Duration.ofDays(1)));
  }

  @Test
  void explicitTransportRegionEndpointsAndTimeoutsAreOwnedByFramework() {
    Source source = new Source();
    try (var clients = new AsyncAwsClients(source, CLOCK, Duration.ofDays(1))) {
      try (var client =
          clients.configured(
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
            settings
                    .advancedOption(
                        software.amazon.awssdk.core.client.config.SdkAdvancedClientOption.SIGNER)
                    .orElseThrow()
                instanceof software.amazon.awssdk.auth.signer.Aws4Signer,
            "finite JSON requests must avoid the SDK's contended shared digest cache");
        assertFalse(settings.executionInterceptors().isEmpty());
      }
      try (var client =
          clients.configured(
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
          clients.configured(
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
  void closingOneNativeClientCannotCloseSharedCredentialRefresh() {
    Source source = new Source();
    var warmed = new WarmedAwsCredentials(source, CLOCK, Duration.ofDays(1));
    warmed.close();
    warmed.close();
    warmed.refresh();
    assertEquals(1, source.calls.get());
    assertEquals(1, source.closes);
    assertThrows(SdkClientException.class, warmed::resolveCredentials);
    var basic =
        software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
            software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create(
                "fixture", "fixture"));
    try (var withoutExpiry = new WarmedAwsCredentials(basic, CLOCK, Duration.ofDays(1))) {
      assertEquals("fixture", withoutExpiry.resolveCredentials().accessKeyId());
    }
  }

  @Test
  void missingExplicitRegionFailsRatherThanStartingInstanceMetadataLookup() {
    org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("AWS_REGION") == null);
    try (var clients = new AsyncAwsClients(new Source(), CLOCK, Duration.ofDays(1))) {
      assertThrows(
          IllegalStateException.class,
          () ->
              clients.configured(
                  DynamoDbAsyncClient.builder(), ObservationRegistry.NOOP, key -> "", "dynamodb"));
    }
  }
}
