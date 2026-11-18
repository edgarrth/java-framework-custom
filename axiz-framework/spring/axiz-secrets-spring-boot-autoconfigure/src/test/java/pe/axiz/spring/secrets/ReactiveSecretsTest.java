package pe.axiz.spring.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import pe.axiz.core.secrets.AsyncSecretResolver;
import pe.axiz.core.secrets.SecretValue;
import reactor.test.StepVerifier;

class ReactiveSecretsTest {
  private static ReactiveWebApplicationContextRunner reactive() {
    return new ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizSecretsAutoConfiguration.class));
  }

  @Test
  void customNativeResolverAvoidsSnapshotsAndSdkResourcesAndKeepsCancellation() {
    var calls = new AtomicInteger();
    var pending = new CompletableFuture<Optional<SecretValue>>();
    reactive()
        .withBean(
            AsyncSecretResolver.class,
            () ->
                name -> {
                  calls.incrementAndGet();
                  return pending;
                })
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed().doesNotHaveBean("axizMountedSecretFiles");
              var lookup = ctx.getBean(ReactiveSecrets.class).resolve("key");
              assertEquals(0, calls.get());
              StepVerifier.create(lookup).thenCancel().verify();
              assertEquals(1, calls.get());
              assertTrue(pending.isCancelled());
            });
  }
}
