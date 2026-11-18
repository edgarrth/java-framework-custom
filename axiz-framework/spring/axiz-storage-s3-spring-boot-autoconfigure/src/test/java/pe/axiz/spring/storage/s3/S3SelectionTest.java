package pe.axiz.spring.storage.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import pe.axiz.core.storage.AsyncObjectStorage;
import pe.axiz.core.storage.InMemoryAsyncObjectStorage;
import pe.axiz.core.storage.InMemoryObjectStorage;
import pe.axiz.core.storage.ObjectStorage;
import pe.axiz.spring.aws.AwsIdentity;
import pe.axiz.spring.storage.AxizStorageAutoConfiguration;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class S3SelectionTest {
  private static final AutoConfigurations CONFIG =
      AutoConfigurations.of(
          AxizStorageAutoConfiguration.class, AxizStorageS3AutoConfiguration.class);

  @Test
  void missingBucketFailsBeforeSelectingCredentialsOrConstructingClients() {
    var identity = mock(AwsIdentity.class);
    new ApplicationContextRunner()
        .withConfiguration(CONFIG)
        .withBean(AwsIdentity.class, () -> identity)
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("bucket is required"));
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(CONFIG)
        .withBean(AwsIdentity.class, () -> identity)
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("bucket is required"));
    verify(identity, times(2)).close();
    verifyNoMoreInteractions(identity);
  }

  @Test
  void customPortsDoNotRequireS3ConfigurationOrAllocateResources() {
    var sync = new InMemoryObjectStorage();
    var async = new InMemoryAsyncObjectStorage();
    new ApplicationContextRunner()
        .withConfiguration(CONFIG)
        .withBean(ObjectStorage.class, () -> sync)
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .doesNotHaveBean(S3Client.class)
                    .doesNotHaveBean(S3Presigner.class));
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(CONFIG)
        .withBean(AsyncObjectStorage.class, () -> async)
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .doesNotHaveBean(S3AsyncClient.class)
                    .doesNotHaveBean(S3Presigner.class));
  }

  @Test
  void selectingAnAbsentIntegrationFails() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizStorageAutoConfiguration.class))
        .withPropertyValues("axiz.storage.type=s3")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("selected integration"));
  }
}
