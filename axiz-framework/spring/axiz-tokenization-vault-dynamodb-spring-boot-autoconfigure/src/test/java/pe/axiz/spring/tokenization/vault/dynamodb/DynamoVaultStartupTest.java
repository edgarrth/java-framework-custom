package pe.axiz.spring.tokenization.vault.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.core.crypto.AsyncDataKeyProvider;
import pe.axiz.core.crypto.DataKeyProvider;
import pe.axiz.spring.aws.AwsIdentity;
import pe.axiz.spring.aws.AxizAwsAsyncTransportProperties;
import pe.axiz.spring.aws.AxizAwsCompletionProperties;
import pe.axiz.spring.aws.AxizAwsIdentityAutoConfiguration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

class DynamoVaultStartupTest {
  @Test
  void verificationIsBoundedMasksFailuresAndPreservesInterrupts() {
    var settings = new AxizTokenizationDynamoDbProperties("vault", Duration.ofMillis(1));
    DynamoVaultStartup.await(CompletableFuture.completedFuture(null), settings);
    var failed = CompletableFuture.<Void>failedFuture(new IllegalStateException("secret-canary"));
    assertThat(
            assertThrows(
                IllegalStateException.class, () -> DynamoVaultStartup.await(failed, settings)))
        .hasNoCause()
        .hasMessageNotContaining("canary");
    assertThrows(
        IllegalStateException.class,
        () -> DynamoVaultStartup.await(new CompletableFuture<>(), settings));
    Thread.currentThread().interrupt();
    try {
      assertThrows(
          IllegalStateException.class,
          () -> DynamoVaultStartup.await(new CompletableFuture<>(), settings));
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void rejectsMissingRoutingAndInvalidStorageBounds() {
    var configuration = new AxizTokenizationDynamoDbAutoConfiguration();
    assertThrows(
        IllegalStateException.class,
        () ->
            configuration.axizDynamoVaultReadiness(
                null, null, null, null, mock(DataKeyProvider.class)));
    var nativeConfiguration = new AxizTokenizationDynamoDbAutoConfiguration.Native();
    assertThrows(
        IllegalStateException.class,
        () ->
            nativeConfiguration.axizDynamoAsyncVaultReadiness(
                null, null, null, null, mock(AsyncDataKeyProvider.class)));
    for (String table : new String[] {null, "", "ab", "invalid/table"})
      assertThrows(
          IllegalArgumentException.class,
          () -> new AxizTokenizationDynamoDbProperties(table, Duration.ofSeconds(1)));
    for (Duration timeout : new Duration[] {null, Duration.ZERO, Duration.ofSeconds(31)})
      assertThrows(
          IllegalArgumentException.class,
          () -> new AxizTokenizationDynamoDbProperties("vault", timeout));
    assertThat(
            new AxizTokenizationDynamoDbProperties("vault", Duration.ofSeconds(30))
                .requestTimeout())
        .isEqualTo(Duration.ofSeconds(30));
  }

  @Test
  void bootstrapCreatesOwnedSyncAndNativeTransportsUsingOnlyTheExplicitLocalIdentity() {
    var provider = mock(AwsCredentialsProvider.class);
    when(provider.resolveCredentials()).thenReturn(AwsBasicCredentials.create("test", "fixture"));
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
        .withBean(AwsCredentialsProvider.class, () -> provider)
        .withPropertyValues("axiz.aws.region=us-east-1", "axiz.aws.endpoint=https://localhost:4566")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var config = new AxizTokenizationDynamoDbAutoConfiguration();
              var nativeConfig = new AxizTokenizationDynamoDbAutoConfiguration.Native();
              var identity = context.getBean(AwsIdentity.class);
              var observations = context.getBeanProvider(ObservationRegistry.class);
              try (var sync =
                      config.axizTokenVaultDynamoClient(
                          identity, context.getEnvironment(), observations);
                  var resources =
                      nativeConfig.axizTokenVaultAwsResources(
                          new AxizAwsCompletionProperties(1, 10),
                          new AxizAwsAsyncTransportProperties(
                              Duration.ofMinutes(5), Duration.ofSeconds(30)),
                          context.getBeanProvider(MeterRegistry.class),
                          identity);
                  var async =
                      nativeConfig.axizTokenVaultDynamoAsyncClient(
                          resources, context.getEnvironment(), observations)) {
                assertThat(sync.serviceName()).isEqualTo("dynamodb");
                assertThat(async.serviceName()).isEqualTo("dynamodb");
                verify(provider, atLeastOnce()).resolveCredentials();
              }
            });
  }
}
