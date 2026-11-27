package pe.axiz.quality.stores;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.testcontainers.containers.GenericContainer;
import pe.axiz.core.errors.RateLimitedException;
import pe.axiz.core.secrets.SecretResolver;
import pe.axiz.core.secrets.SecretValue;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenOperation;
import pe.axiz.core.tokenization.TokenType;
import pe.axiz.core.tokenization.vault.AsyncTokenBudget;
import pe.axiz.core.tokenization.vault.TokenBudget;
import pe.axiz.core.tokenization.vault.TokenBudgetLeaseAlarm;
import pe.axiz.core.tokenization.vault.TokenCaller;
import pe.axiz.core.tokenization.vault.VaultScope;
import pe.axiz.quality.containers.TestContainerImages;
import pe.axiz.spring.foundation.ReactiveInfrastructureBootstrap;
import pe.axiz.spring.test.BlockHoundExtension;
import pe.axiz.spring.tokenization.vault.redis.AxizTokenizationRedisAutoConfiguration;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/**
 * Transportes dedicados autenticados reales, selección del stack de Spring y propiedad entre varios
 * contextos.
 */
@ExtendWith(BlockHoundExtension.class)
class ValkeyTokenBudgetSpringIT {
  private static final String PASSWORD = java.util.UUID.randomUUID().toString();
  private static final AutoConfigurations CONFIG =
      AutoConfigurations.of(AxizTokenizationRedisAutoConfiguration.class);
  private static final TokenDomain DOMAIN = new TokenDomain("payments");
  private static final TokenCaller CALLER =
      new TokenCaller("alice", "app", "tenant", Set.of("SCOPE_tokens:lookup"), Set.of(DOMAIN));
  private static final VaultScope SCOPE =
      new VaultScope(new TokenType("DNI"), "tenant", DOMAIN, "dni.pe", 1);
  private static final AtomicInteger NAMES = new AtomicInteger();
  private static GenericContainer<?> valkey;

  @BeforeAll
  static void start() {
    Docker.require();
    ReactiveInfrastructureBootstrap.initialize();
    valkey =
        TestContainerImages.valkey()
            .withExposedPorts(6379)
            .withCommand(
                "valkey-server", "--requirepass", PASSWORD, "--maxmemory-policy", "noeviction");
    valkey.start();
  }

  @AfterAll
  static void stop() {
    try {
      assertTrue(
          BlockHoundExtension.detectedBlockingCalls().isEmpty(),
          () -> BlockHoundExtension.detectedBlockingCalls().toString());
    } finally {
      if (valkey != null) valkey.stop();
    }
  }

  private static String[] properties(String namespace) {
    String prefix = "axiz.tokenization.vault.redis.";
    var properties =
        new ArrayList<>(
            List.of(
                "axiz.config.environment=test",
                "axiz.transport.allow-insecure-data-stores=true",
                prefix + "namespace=" + namespace,
                prefix + "connection.host=127.0.0.1",
                prefix + "connection.port=" + valkey.getMappedPort(6379),
                prefix + "connection.tls=false",
                prefix + "connection.username=default",
                prefix + "connection.password-secret=budget/password",
                prefix + "connection.timeout=2s",
                prefix + "policy.subject-concurrent=100",
                prefix + "policy.client-concurrent=100",
                prefix + "policy.tenant-concurrent=100",
                prefix + "policy.concurrency-retry-after=1s"));
    for (String group : List.of("query", "reveal", "metadata", "writes"))
      for (String dimension : List.of("subject", "client", "tenant"))
        for (String window : List.of("burst", "minute", "day"))
          properties.add(prefix + "policy." + group + "." + dimension + "." + window + "=10000");
    return properties.toArray(String[]::new);
  }

  private static SecretResolver secrets(List<SecretValue> issued, String password) {
    return name -> {
      assertThat(name).isEqualTo("budget/password");
      var secret = SecretValue.of(password);
      issued.add(secret);
      return Optional.of(secret);
    };
  }

  @Test
  void contextsHaveIndependentConnectionsAndLifecycleButShareActualAdmissionState() {
    var firstSecrets = new CopyOnWriteArrayList<SecretValue>();
    var secondSecrets = new CopyOnWriteArrayList<SecretValue>();
    var base =
        new ApplicationContextRunner()
            .withConfiguration(CONFIG)
            .withPropertyValues(properties("spring-" + NAMES.incrementAndGet()))
            .withPropertyValues(
                "axiz.tokenization.vault.redis.policy.query.subject.burst=2",
                "axiz.tokenization.vault.redis.policy.query.subject.minute=2",
                "axiz.tokenization.vault.redis.policy.query.subject.day=2")
            .withBean(TokenBudgetLeaseAlarm.class, () -> (namespace, lease) -> {});
    base.withBean(SecretResolver.class, () -> secrets(secondSecrets, PASSWORD))
        .run(
            second -> {
              assertThat(second)
                  .hasNotFailed()
                  .hasSingleBean(TokenBudget.class)
                  .doesNotHaveBean(AsyncTokenBudget.class)
                  .doesNotHaveBean(RedisClient.class)
                  .doesNotHaveBean(StatefulRedisConnection.class);
              var secondBudget = second.getBean(TokenBudget.class);
              base.withBean(SecretResolver.class, () -> secrets(firstSecrets, PASSWORD))
                  .run(
                      first -> {
                        assertThat(first).hasNotFailed();
                        first
                            .getBean(TokenBudget.class)
                            .acquire(CALLER, TokenOperation.LOOKUP, SCOPE)
                            .close();
                        secondBudget.acquire(CALLER, TokenOperation.TOKENIZE, SCOPE).close();
                        assertThrows(
                            RateLimitedException.class,
                            () -> secondBudget.acquire(CALLER, TokenOperation.LOOKUP, SCOPE));
                      });
              firstSecrets.forEach(
                  secret -> assertThrows(IllegalStateException.class, secret::revealChars));
              secondBudget.acquire(CALLER, TokenOperation.DESCRIBE, SCOPE).close();
            });
    secondSecrets.forEach(secret -> assertThrows(IllegalStateException.class, secret::revealChars));
  }

  @Test
  void webfluxUsesOnlyNativeBudgetAndSurvivesConcurrentEventLoopSubmissions() {
    var issued = new CopyOnWriteArrayList<SecretValue>();
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(CONFIG)
        .withPropertyValues(properties("native-" + NAMES.incrementAndGet()))
        .withBean(SecretResolver.class, () -> secrets(issued, PASSWORD))
        .withBean(TokenBudgetLeaseAlarm.class, () -> (namespace, lease) -> {})
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(AsyncTokenBudget.class)
                  .doesNotHaveBean(TokenBudget.class)
                  .doesNotHaveBean(StatefulRedisConnection.class);
              var budget = context.getBean(AsyncTokenBudget.class);
              StepVerifier.create(
                      Flux.range(0, 64)
                          .flatMap(
                              ignored ->
                                  Mono.defer(
                                          () ->
                                              Mono.fromCompletionStage(
                                                  budget.acquire(
                                                      CALLER, TokenOperation.LOOKUP, SCOPE)))
                                      .subscribeOn(Schedulers.parallel())
                                      .flatMap(
                                          permit -> Mono.fromCompletionStage(permit.release())),
                              64))
                  .expectComplete()
                  .verify(Duration.ofSeconds(30));
            });
    issued.forEach(secret -> assertThrows(IllegalStateException.class, secret::revealChars));
  }

  @Test
  void failedAuthenticationClosesTheOwnedSecretAndDoesNotFallbackToMemory() {
    var issued = new CopyOnWriteArrayList<SecretValue>();
    new ApplicationContextRunner()
        .withConfiguration(CONFIG)
        .withPropertyValues(properties("denied-" + NAMES.incrementAndGet()))
        .withBean(SecretResolver.class, () -> secrets(issued, "synthetic-wrong-password-canary"))
        .withBean(TokenBudgetLeaseAlarm.class, () -> (namespace, lease) -> {})
        .run(
            context -> {
              assertThat(context).hasFailed();
              var text = new StringWriter();
              try (var writer = new PrintWriter(text)) {
                Objects.requireNonNull(context.getStartupFailure()).printStackTrace(writer);
              }
              assertThat(text.toString()).doesNotContain("synthetic-wrong-password-canary");
            });
    assertThat(issued).hasSize(1);
    issued.forEach(secret -> assertThrows(IllegalStateException.class, secret::revealChars));
  }
}
