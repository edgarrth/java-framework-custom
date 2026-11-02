package pe.axiz.spring.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.util.TestPropertyValues;
import pe.axiz.adapter.observability.aws.AsyncAwsClients;
import pe.axiz.core.secrets.AsyncSecretResolver;
import pe.axiz.core.secrets.SecretNotFoundException;
import pe.axiz.core.secrets.SecretValue;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerAsyncClient;
import software.amazon.awssdk.services.ssm.SsmAsyncClient;

class ReactiveSecretsTest {
  private static ReactiveWebApplicationContextRunner reactive() {
    return new ReactiveWebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizSecretsNativeAwsAutoConfiguration.class,
                AxizSecretsAwsAutoConfiguration.class,
                AxizSecretsAutoConfiguration.class))
        .withPropertyValues("axiz.aws.region=us-east-1", "axiz.secrets.environment=false");
  }

  @Test
  void mountedSnapshotWinsAndLookupDoesNotReadTheFileAgain(@TempDir Path directory)
      throws Exception {
    Path secret = directory.resolve("payments.password");
    Files.writeString(secret, "mounted");
    var remoteCalls = new AtomicInteger();
    reactive()
        .withPropertyValues("axiz.secrets.directory=" + directory)
        .withBean(
            AsyncSecretSource.class,
            () ->
                new AsyncSecretSource(
                    "remote",
                    name -> {
                      remoteCalls.incrementAndGet();
                      return CompletableFuture.completedFuture(
                          name.equals("missing")
                              ? Optional.empty()
                              : Optional.of(SecretValue.of("remote")));
                    }))
        .run(
            ctx -> {
              assertThat(ctx)
                  .hasNotFailed()
                  .hasSingleBean(AsyncSecretResolver.class)
                  .hasSingleBean(ReactiveSecrets.class);
              Files.delete(
                  secret); // The request must use the startup snapshot, not filesystem I/O.
              var secrets = ctx.getBean(ReactiveSecrets.class);
              StepVerifier.create(secrets.require("payments/password"))
                  .assertNext(value -> assertEquals("mounted", value.reveal()))
                  .verifyComplete();
              assertEquals(0, remoteCalls.get());
              StepVerifier.create(secrets.require("remote"))
                  .assertNext(value -> assertEquals("remote", value.reveal()))
                  .verifyComplete();
              StepVerifier.create(secrets.resolve("missing")).verifyComplete();
              StepVerifier.create(secrets.require("missing"))
                  .expectError(SecretNotFoundException.class)
                  .verify();
            });
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
                  .doesNotHaveBean(AxizSecretsAutoConfiguration.MountedSecretFiles.class)
                  .doesNotHaveBean(AsyncAwsClients.class)
                  .doesNotHaveBean(SecretsManagerAsyncClient.class);
              var lookup = ctx.getBean(ReactiveSecrets.class).resolve("key");
              assertEquals(0, calls.get());
              StepVerifier.create(lookup).thenCancel().verify();
              assertEquals(1, calls.get());
              assertTrue(pending.isCancelled());
            });
  }

  @Test
  @ResourceLock(Resources.SYSTEM_PROPERTIES)
  void twoNativeSourcesShareOneWarmedResourceOwner(@TempDir Path directory) {
    TestPropertyValues.of("aws.accessKeyId=test", "aws.secretAccessKey=test")
        .applyToSystemProperties(
            () ->
                reactive()
                    .withPropertyValues(
                        "axiz.secrets.directory=" + directory,
                        "axiz.secrets.secrets-manager.enabled=true",
                        "axiz.secrets.parameter-store.enabled=true",
                        "axiz.secrets.parameter-store.path=/payments/",
                        "axiz.aws.endpoint=http://localhost:4566")
                    .run(
                        ctx ->
                            assertThat(ctx)
                                .hasNotFailed()
                                .hasSingleBean(AsyncAwsClients.class)
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
                    .doesNotHaveBean(
                        software.amazon.awssdk.services.secretsmanager.SecretsManagerClient.class)
                    .doesNotHaveBean(SecretsManagerAsyncClient.class)
                    .doesNotHaveBean(SsmAsyncClient.class));
  }

  @Test
  void mountedAndEnvironmentSupportDoesNotRequireAwsAdapterClasses(@TempDir Path directory) {
    reactive()
        .withClassLoader(
            new FilteredClassLoader(
                "pe.axiz.adapter.secrets.aws",
                "software.amazon.awssdk.services.secretsmanager",
                "software.amazon.awssdk.services.ssm"))
        .withPropertyValues("axiz.secrets.directory=" + directory)
        .run(
            ctx ->
                assertThat(ctx)
                    .hasNotFailed()
                    .hasSingleBean(AsyncSecretResolver.class)
                    .hasSingleBean(ReactiveSecrets.class)
                    .doesNotHaveBean(AsyncAwsClients.class));
  }
}
