package pe.axiz.adapter.aws.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import pe.axiz.adapter.aws.identity.AwsCredentialSource;
import pe.axiz.adapter.aws.identity.AwsCredentialsPlugin;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

class AwsResourceLifecycleTest {
  @Test
  void closingResourceOwnerClosesItsLastInstrumentationBindingExactlyOnce() {
    var closes = new AtomicInteger();
    var credentialsClosed = new AtomicInteger();
    var owner =
        new AsyncAwsClients(
            source(credentialsClosed),
            Clock.systemUTC(),
            Duration.ofDays(1),
            AsyncAwsTransportSettings.defaults());
    owner.observeCompletions(
        pool ->
            new CompletionObserver() {
              @Override
              public void rejected() {}

              @Override
              public void completed(long nanos) {}

              @Override
              public void close() {
                closes.incrementAndGet();
              }
            });
    owner.close();
    owner.close();
    assertEquals(1, closes.get());
    assertEquals(1, credentialsClosed.get());
  }

  @Test
  void synchronousClientsOwnTheirResourceAndRetainExistingBuilderConfiguration() {
    var closes = new AtomicInteger();
    var source = source(closes);
    var interceptor = new ExecutionInterceptor() {};
    try (var client =
        AwsClients.build(
            DynamoDbClient.builder()
                .addPlugin(new AwsCredentialsPlugin(source))
                .overrideConfiguration(c -> c.addExecutionInterceptor(interceptor)),
            "us-east-1",
            null,
            Duration.ofSeconds(8))) {
      assertEquals(
          "fixture",
          client
              .serviceClientConfiguration()
              .credentialsProvider()
              .resolveIdentity()
              .join()
              .accessKeyId());
      assertTrue(
          client
              .serviceClientConfiguration()
              .overrideConfiguration()
              .executionInterceptors()
              .contains(interceptor));
      assertEquals(
          Duration.ofSeconds(8),
          client
              .serviceClientConfiguration()
              .overrideConfiguration()
              .apiCallTimeout()
              .orElseThrow());
    }
    assertEquals(1, closes.get());
    assertThrows(SdkClientException.class, () -> source.provider().resolveCredentials());
  }

  @Test
  void invalidConstructionClosesIdentityAndPreservesOriginalFailure() {
    var closes = new AtomicInteger();
    var source =
        AwsCredentialSource.owned(
            () -> AwsBasicCredentials.create("fixture", "fixture"),
            () -> {
              closes.incrementAndGet();
              throw new IllegalStateException("cleanup fixture");
            });
    var failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                AwsClients.build(
                    DynamoDbClient.builder().addPlugin(new AwsCredentialsPlugin(source)),
                    "us-east-1",
                    "http://localhost",
                    Duration.ZERO));
    assertEquals(1, closes.get());
    assertEquals("cleanup fixture", failure.getSuppressed()[0].getMessage());
    assertThrows(SdkClientException.class, () -> source.provider().resolveCredentials());
    var second = source(closes);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AwsClients.build(
                DynamoDbClient.builder().addPlugin(new AwsCredentialsPlugin(second)),
                "us-east-1",
                "invalid endpoint"));
    assertEquals(2, closes.get());
  }

  @Test
  void asynchronousResourcesPreserveInterceptorsAndOwnTheirSourceAcrossClients() {
    var closes = new AtomicInteger();
    var source = source(closes);
    var interceptor = new ExecutionInterceptor() {};
    var owner =
        new AsyncAwsClients(
            source, Clock.systemUTC(), Duration.ofDays(1), AsyncAwsTransportSettings.defaults());
    try (owner) {
      try (var client =
          owner.configured(
              DynamoDbAsyncClient.builder()
                  .overrideConfiguration(c -> c.addExecutionInterceptor(interceptor)),
              Map.of(
                      "axiz.aws.region",
                      "us-east-1",
                      "axiz.aws.endpoint",
                      "https://localhost:4566",
                      "axiz.aws.dynamodb.endpoint",
                      " ")
                  ::get,
              "dynamodb",
              Duration.ofSeconds(4))) {
        assertEquals(Region.US_EAST_1, client.serviceClientConfiguration().region());
        assertTrue(
            client
                .serviceClientConfiguration()
                .overrideConfiguration()
                .executionInterceptors()
                .contains(interceptor));
        assertEquals(
            Duration.ofSeconds(4),
            client
                .serviceClientConfiguration()
                .overrideConfiguration()
                .apiCallAttemptTimeout()
                .orElseThrow());
      }
      assertEquals(0, closes.get());
      assertEquals("fixture", owner.credentialsProvider().resolveCredentials().accessKeyId());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              owner.configured(
                  DynamoDbAsyncClient.builder(),
                  Map.of("axiz.aws.region", "us-east-1")::get,
                  "dynamodb",
                  Duration.ZERO));
    }
    owner.close();
    assertEquals(1, closes.get());
    assertThrows(IllegalStateException.class, () -> owner.observeCompletions(pool -> null));
    assertThrows(SdkClientException.class, () -> owner.credentialsProvider().resolveCredentials());
    var invalid = source(closes);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AsyncAwsClients(
                invalid,
                Clock.systemUTC(),
                Duration.ofSeconds(15),
                0,
                1,
                AsyncAwsTransportSettings.defaults()));
    assertEquals(2, closes.get());
  }

  private static AwsCredentialSource source(AtomicInteger closes) {
    return AwsCredentialSource.owned(
        () -> AwsBasicCredentials.create("fixture", "fixture"), closes::incrementAndGet);
  }
}
