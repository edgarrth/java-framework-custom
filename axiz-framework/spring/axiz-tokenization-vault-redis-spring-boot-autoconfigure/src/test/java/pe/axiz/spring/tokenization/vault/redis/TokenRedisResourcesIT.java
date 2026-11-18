package pe.axiz.spring.tokenization.vault.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import pe.axiz.core.commons.net.DataTransportSecurityPolicy;
import pe.axiz.core.secrets.SecretValue;
import pe.axiz.core.tokenization.vault.TokenBudgetPolicy;
import pe.axiz.core.tokenization.vault.TokenBudgetPolicy.Limits;
import pe.axiz.core.tokenization.vault.TokenBudgetPolicy.Windows;
import pe.axiz.quality.containers.TestContainerImages;
import pe.axiz.spring.tokenization.vault.redis.AxizTokenizationRedisProperties.Redis;

class TokenRedisResourcesIT {
  @Test
  void dedicatedAuthenticatedConnectionSupportsBothStacksAndErasesCredentialsOnClose() {
    try (var redis =
        TestContainerImages.valkey()
            .withCommand("valkey-server", "--requirepass", "synthetic-test-password")) {
      redis.start();
      var connection =
          new Redis(
              redis.getHost(),
              redis.getMappedPort(6379),
              false,
              "default",
              "budget/password",
              Duration.ofSeconds(2),
              10);
      var windows = new Windows(1, 10, 100);
      var limits = new Limits(windows, windows, windows);
      var policy =
          TokenBudgetPolicy.builder()
              .query(limits)
              .reveal(limits)
              .metadata(limits)
              .writes(limits)
              .subjectConcurrent(1)
              .clientConcurrent(2)
              .tenantConcurrent(3)
              .concurrencyRetryAfter(Duration.ofSeconds(1))
              .build();
      var properties = new AxizTokenizationRedisProperties("vault", policy, connection, false);
      var secret = SecretValue.of("synthetic-test-password");
      var configuration = new AxizTokenizationRedisAutoConfiguration();
      var resources =
          configuration.axizTokenBudgetRedisResources(
              properties,
              new MockEnvironment()
                  .withProperty("axiz.config.environment", "test")
                  .withProperty("axiz.transport.allow-insecure-data-stores", "true"),
              name -> Optional.of(secret));
      try (resources) {
        assertThat(resources.connection().sync().ping()).isEqualTo("PONG");
        assertThat(
                configuration
                    .axizRedisTokenBudget(resources, properties, (namespace, id) -> {})
                    .distributed())
            .isTrue();
        assertThat(
                configuration
                    .axizRedisAsyncTokenBudget(resources, properties, (namespace, id) -> {})
                    .distributed())
            .isTrue();
      }
      resources.close();
      assertThat(resources.connection().isOpen()).isFalse();
      assertThrows(IllegalStateException.class, secret::revealChars);
      var wrong = SecretValue.of("synthetic-wrong-password");
      var failure =
          assertThrows(
              IllegalStateException.class,
              () ->
                  TokenRedisResources.open(
                      connection,
                      new DataTransportSecurityPolicy("test", true),
                      name -> Optional.of(wrong)));
      assertThat(failure).hasMessageNotContaining("password").hasNoCause();
      assertThrows(IllegalStateException.class, wrong::revealChars);
      var missing =
          assertThrows(
              IllegalStateException.class,
              () ->
                  TokenRedisResources.open(
                      connection,
                      new DataTransportSecurityPolicy("test", true),
                      name -> {
                        throw new IllegalStateException("secret-canary");
                      }));
      assertThat(missing).hasMessageNotContaining("canary").hasNoCause();
    }
  }
}
