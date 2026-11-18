package pe.axiz.spring.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import pe.axiz.core.secrets.AsyncSecretResolver;
import pe.axiz.core.secrets.SecretValue;

class SecretSourcePolicyTest {
  private final ReactiveWebApplicationContextRunner runner =
      new ReactiveWebApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizSecretsAutoConfiguration.class));

  @Test
  void aRefreshedSourceIsNeverHiddenBehindTheRemoteCache() {
    runner
        .withUserConfiguration(Sources.class)
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed().hasSingleBean(AsyncSecretResolver.class);
              var resolver = ctx.getBean(AsyncSecretResolver.class);
              var state = ctx.getBean(SourceState.class);
              assertThat(resolve(resolver, "local")).isEqualTo("first");
              state.local.set("rotated");
              assertThat(resolve(resolver, "local")).isEqualTo("rotated");
              assertThat(state.remoteCalls.get()).isZero();
              assertThat(resolve(resolver, "remote")).isEqualTo("remote-1");
              assertThat(resolve(resolver, "remote")).isEqualTo("remote-1");
              assertThat(state.remoteCalls.get()).isEqualTo(1);
              state.overrideRemote.set(true);
              assertThat(resolve(resolver, "remote")).isEqualTo("rotated");
              assertThat(state.remoteCalls.get()).isEqualTo(1);
            });
  }

  @Test
  void noIntegrationHasNoImplicitSourceAndInvalidCachePolicyFailsAtStartup() {
    runner.run(
        ctx -> {
          assertThat(ctx).hasNotFailed().doesNotHaveBean(AsyncSecretSource.class);
          assertThat(
                  ctx.getBean(AsyncSecretResolver.class)
                      .resolve("absent")
                      .toCompletableFuture()
                      .join())
              .isEmpty();
        });
    runner
        .withPropertyValues("axiz.secrets.cache-ttl=0s")
        .run(
            ctx ->
                assertThat(ctx)
                    .hasFailed()
                    .getFailure()
                    .hasRootCauseMessage("axiz.secrets.cache-ttl must be positive"));
  }

  @Test
  void closingTheContextErasesItsRemoteCacheAndPreservesTheDeliveredCopy() {
    var raw = SecretValue.of("owned-cache");
    var copy = new AtomicReference<SecretValue>();
    runner
        .withBean(
            AsyncSecretSource.class,
            () ->
                new AsyncSecretSource(
                    "remote", name -> CompletableFuture.completedFuture(Optional.of(raw)), true))
        .run(
            ctx -> {
              copy.set(
                  ctx.getBean(AsyncSecretResolver.class)
                      .require("key")
                      .toCompletableFuture()
                      .join());
              assertEquals("owned-cache", copy.get().reveal());
            });
    assertThrows(IllegalStateException.class, raw::reveal);
    try (var delivered = copy.get()) {
      assertEquals("owned-cache", delivered.reveal());
    }
  }

  private static String resolve(AsyncSecretResolver resolver, String name) {
    try (SecretValue value = resolver.require(name).toCompletableFuture().join()) {
      return value.reveal();
    }
  }

  static final class SourceState {
    final AtomicReference<String> local = new AtomicReference<>("first");
    final AtomicInteger remoteCalls = new AtomicInteger();
    final java.util.concurrent.atomic.AtomicBoolean overrideRemote =
        new java.util.concurrent.atomic.AtomicBoolean();
  }

  @Configuration(proxyBeanMethods = false)
  static class Sources {
    @Bean
    SourceState state() {
      return new SourceState();
    }

    @Bean
    @Order(0)
    AsyncSecretSource snapshot(SourceState state) {
      return new AsyncSecretSource(
          "snapshot",
          name ->
              CompletableFuture.completedFuture(
                  name.equals("local") || state.overrideRemote.get()
                      ? Optional.of(SecretValue.of(state.local.get()))
                      : Optional.empty()),
          false);
    }

    @Bean
    @Order(100)
    AsyncSecretSource remote(SourceState state) {
      return new AsyncSecretSource(
          "remote",
          name ->
              CompletableFuture.completedFuture(
                  Optional.of(SecretValue.of("remote-" + state.remoteCalls.incrementAndGet()))),
          true);
    }
  }
}
