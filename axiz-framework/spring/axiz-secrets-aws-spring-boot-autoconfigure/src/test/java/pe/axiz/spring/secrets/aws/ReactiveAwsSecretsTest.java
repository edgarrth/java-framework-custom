package pe.axiz.spring.secrets.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pe.axiz.adapter.aws.transport.AsyncAwsClients;
import pe.axiz.core.secrets.AsyncSecretResolver;
import pe.axiz.core.secrets.SecretValue;
import pe.axiz.spring.aws.AwsIdentityFor;
import pe.axiz.spring.aws.AwsService;
import pe.axiz.spring.aws.AxizAwsIdentityAutoConfiguration;
import pe.axiz.spring.secrets.AxizSecretsAutoConfiguration;
import pe.axiz.spring.secrets.ReactiveSecrets;
import reactor.test.StepVerifier;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerAsyncClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.ssm.SsmAsyncClient;

class ReactiveAwsSecretsTest {
  @Test
  void nativeParameterStoreRejectsMissingAndBlankPaths() {
    for (String path : new String[] {"", " "}) {
      reactive()
          .withUserConfiguration(ServiceIdentities.class)
          .withPropertyValues(
              "axiz.secrets.parameter-store.enabled=true",
              "axiz.secrets.parameter-store.path=" + path)
          .run(
              ctx ->
                  assertThat(ctx)
                      .hasFailed()
                      .getFailure()
                      .rootCause()
                      .hasMessageContaining("axiz.secrets.parameter-store.path is required"));
    }
    reactive()
        .withUserConfiguration(ServiceIdentities.class)
        .withPropertyValues("axiz.secrets.parameter-store.enabled=true")
        .run(
            ctx ->
                assertThat(ctx)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("axiz.secrets.parameter-store.path is required"));
  }

  @Test
  void eachAwsSecretServiceUsesItsOwnQualifiedIdentity(@TempDir Path directory) {
    reactive()
        .withUserConfiguration(ServiceIdentities.class)
        .withPropertyValues(
            "axiz.secrets.directory=" + directory,
            "axiz.secrets.secrets-manager.enabled=true",
            "axiz.secrets.parameter-store.enabled=true",
            "axiz.secrets.parameter-store.path=/payments/",
            "axiz.aws.endpoint=https://localhost:4566")
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertEquals(
                  "MANAGER",
                  ctx.getBean(SecretsManagerAsyncClient.class)
                      .serviceClientConfiguration()
                      .credentialsProvider()
                      .resolveIdentity()
                      .join()
                      .accessKeyId());
              assertEquals(
                  "PARAMETERS",
                  ctx.getBean(SsmAsyncClient.class)
                      .serviceClientConfiguration()
                      .credentialsProvider()
                      .resolveIdentity()
                      .join()
                      .accessKeyId());
              assertEquals(2, ctx.getBeansOfType(AsyncAwsClients.class).size());
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class ServiceIdentities {
    @Bean
    @AwsIdentityFor(service = AwsService.SECRETS_MANAGER)
    AwsCredentialsProvider manager() {
      return () -> AwsBasicCredentials.create("MANAGER", "fixture");
    }

    @Bean
    @AwsIdentityFor(service = AwsService.SSM)
    AwsCredentialsProvider parameters() {
      return () -> AwsBasicCredentials.create("PARAMETERS", "fixture");
    }
  }

  private static ReactiveWebApplicationContextRunner reactive() {
    return new ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
        .withConfiguration(
            AutoConfigurations.of(
                AxizSecretsNativeAwsAutoConfiguration.class,
                AxizSecretsAwsAutoConfiguration.class,
                AxizSecretsAutoConfiguration.class))
        .withPropertyValues("axiz.aws.region=us-east-1", "axiz.secrets.environment=false");
  }

  @Test
  @ResourceLock(Resources.SYSTEM_PROPERTIES)
  void twoNativeSourcesHaveSeparateWarmedOwners(@TempDir Path directory) {
    TestPropertyValues.of("aws.accessKeyId=test", "aws.secretAccessKey=test")
        .applyToSystemProperties(
            () ->
                reactive()
                    .withPropertyValues(
                        "axiz.secrets.directory=" + directory,
                        "axiz.secrets.secrets-manager.enabled=true",
                        "axiz.secrets.parameter-store.enabled=true",
                        "axiz.secrets.parameter-store.path=/payments/",
                        "axiz.aws.endpoint=https://localhost:4566")
                    .run(
                        ctx ->
                            assertThat(ctx)
                                .hasNotFailed()
                                .hasBean("axizSecretsManagerAsyncAwsResources")
                                .hasBean("axizSsmAsyncAwsResources")
                                .hasSingleBean(SecretsManagerAsyncClient.class)
                                .hasSingleBean(SsmAsyncClient.class)
                                .hasSingleBean(AsyncSecretResolver.class)
                                .hasSingleBean(ReactiveSecrets.class)));
  }

  @Test
  void disabledSecretsInitializeNeitherBootstrapNorNativeAwsResources() {
    reactive()
        .withPropertyValues(
            "axiz.secrets.enabled=false",
            "axiz.secrets.secrets-manager.enabled=true",
            "axiz.secrets.parameter-store.enabled=true")
        .run(
            ctx ->
                assertThat(ctx)
                    .hasNotFailed()
                    .doesNotHaveBean(AsyncAwsClients.class)
                    .doesNotHaveBean(AsyncSecretResolver.class)
                    .doesNotHaveBean(ReactiveSecrets.class)
                    .doesNotHaveBean(SecretsManagerClient.class)
                    .doesNotHaveBean(SecretsManagerAsyncClient.class)
                    .doesNotHaveBean(SsmAsyncClient.class));
  }

  @Test
  void customNativeResolverAvoidsSnapshotsAndSdkResourcesAndKeepsCancellation() {
    var calls = new AtomicInteger();
    var pending = new CompletableFuture<Optional<SecretValue>>();
    reactive()
        .withPropertyValues("axiz.secrets.secrets-manager.enabled=true")
        .withBean(
            AsyncSecretResolver.class,
            () ->
                name -> {
                  calls.incrementAndGet();
                  return pending;
                })
        .run(
            ctx -> {
              assertThat(ctx)
                  .hasNotFailed()
                  .doesNotHaveBean("axizMountedSecretFiles")
                  .doesNotHaveBean(AsyncAwsClients.class)
                  .doesNotHaveBean(SecretsManagerAsyncClient.class);
              var lookup = ctx.getBean(ReactiveSecrets.class).resolve("key");
              assertEquals(0, calls.get());
              StepVerifier.create(lookup).thenCancel().verify();
              assertEquals(1, calls.get());
              assertTrue(pending.isCancelled());
            });
  }
}
