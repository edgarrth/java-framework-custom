package pe.axiz.spring.secrets.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import pe.axiz.core.secrets.AsyncSecretResolver;
import pe.axiz.core.secrets.SecretNotFoundException;
import pe.axiz.core.secrets.SecretValue;
import pe.axiz.spring.secrets.AsyncSecretSource;
import pe.axiz.spring.secrets.AxizSecretsAutoConfiguration;
import pe.axiz.spring.secrets.ReactiveSecrets;
import reactor.test.StepVerifier;

class ReactiveFileSecretsTest {
  private static ReactiveWebApplicationContextRunner reactive() {
    return new ReactiveWebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizSecretsFileAutoConfiguration.class, AxizSecretsAutoConfiguration.class))
        .withPropertyValues("axiz.secrets.environment=false");
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
                    },
                    true))
        .run(
            ctx -> {
              assertThat(ctx)
                  .hasNotFailed()
                  .hasSingleBean(AsyncSecretResolver.class)
                  .hasSingleBean(ReactiveSecrets.class);
              Files.delete(
                  secret); // La solicitud debe usar la instantánea de arranque, sin E/S de disco.
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
                    .hasSingleBean(ReactiveSecrets.class));
  }
}
