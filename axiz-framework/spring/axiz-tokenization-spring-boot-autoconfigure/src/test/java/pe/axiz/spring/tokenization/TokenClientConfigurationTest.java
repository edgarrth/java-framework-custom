package pe.axiz.spring.tokenization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.tokenization.AsyncTokenizer;
import pe.axiz.core.tokenization.SensitiveValue;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenType;
import pe.axiz.core.tokenization.Tokenizer;
import pe.axiz.spring.httpclient.AxizHttpClientAutoConfiguration;

class TokenClientConfigurationTest {
  private static final AutoConfigurations CONFIG =
      AutoConfigurations.of(
          AxizHttpClientAutoConfiguration.class,
          AxizTokenizationRemoteAutoConfiguration.class,
          AxizTokenizationLocalAutoConfiguration.class);

  @Test
  void selectedCapabilityRequiresExplicitModeAndRemoteAuthenticationPolicy() {
    var runner = new ApplicationContextRunner().withConfiguration(CONFIG);
    runner.run(context -> assertThat(context).hasFailed());
    runner
        .withPropertyValues("axiz.tokenization.mode=unsupported")
        .run(context -> assertThat(context).hasFailed());
    var remote =
        runner.withPropertyValues(
            "axiz.tokenization.mode=remote",
            "axiz.tokenization.remote.client=token-vault",
            "axiz.http-client.clients.token-vault.base-url=https://vault.example.test",
            "axiz.http-client.clients.token-vault.retry=NONE",
            "axiz.http-client.clients.token-vault.max-attempts=1",
            "axiz.http-client.clients.token-vault.max-concurrent-calls=8",
            "axiz.http-client.clients.token-vault.auth.type=TOKEN_RELAY");
    remote.run(
        context ->
            assertThat(context)
                .hasNotFailed()
                .hasSingleBean(Tokenizer.class)
                .doesNotHaveBean(AsyncTokenizer.class));
    for (String override :
        new String[] {
          "max-connections=257", "max-concurrent-calls=257", "max-attempts=2",
          "connect-timeout=11s", "read-timeout=31s", "total-timeout=61s",
          "base-url=https://vault.example.test/path",
              "base-url=https://vault.example.test?query=value"
        })
      remote
          .withPropertyValues("axiz.http-client.clients.token-vault." + override)
          .run(context -> assertThat(context).as(override).hasFailed());
    remote
        .withPropertyValues("axiz.http-client.clients.token-vault.auth.type=NONE")
        .run(context -> assertThat(context).hasFailed());
    remote
        .withPropertyValues("axiz.http-client.clients.token-vault.retry=IDEMPOTENT_ONLY")
        .run(context -> assertThat(context).hasFailed());
    remote
        .withPropertyValues("axiz.http-client.clients.token-vault.max-concurrent-calls=0")
        .run(context -> assertThat(context).hasFailed());
    remote
        .withPropertyValues("axiz.http-client.clients.token-vault.reveal-card-data=true")
        .run(context -> assertThat(context).hasFailed());
    remote
        .withPropertyValues("axiz.tokenization.remote.async-facade=true")
        .run(context -> assertThat(context).hasFailed());
    remote
        .withPropertyValues(
            "axiz.http-client.clients.token-vault.propagate-headers[0]=Idempotency-Key")
        .run(context -> assertThat(context).hasFailed());
    remote
        .withPropertyValues(
            "axiz.http-client.clients.token-vault.base-url=http://vault.example.test")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void reactiveRemoteDoesNotPublishAnImperativePortOrCaptureThreadLocalIdentity() {
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(CONFIG)
        .withPropertyValues(
            "axiz.tokenization.mode=remote",
            "axiz.tokenization.remote.client=token-vault",
            "axiz.http-client.clients.token-vault.base-url=https://vault.example.test",
            "axiz.http-client.clients.token-vault.retry=NONE",
            "axiz.http-client.clients.token-vault.max-attempts=1",
            "axiz.http-client.clients.token-vault.max-concurrent-calls=8",
            "axiz.http-client.clients.token-vault.auth.type=TOKEN_RELAY")
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(ReactiveTokenizer.class)
                    .doesNotHaveBean(Tokenizer.class)
                    .doesNotHaveBean(AsyncTokenizer.class));
  }

  @Test
  void localDoubleRequiresAnAllowedEnvironmentAndRejectsRealValues() {
    var runner =
        new ApplicationContextRunner()
            .withConfiguration(CONFIG)
            .withPropertyValues("axiz.tokenization.mode=local");
    for (String environment : new String[] {"prod", "qa", "staging", "unknown"})
      runner
          .withPropertyValues("axiz.config.environment=" + environment)
          .run(context -> assertThat(context).hasFailed());
    runner
        .withPropertyValues("axiz.config.environment=test")
        .run(
            context -> {
              assertThat(context).hasNotFailed().hasSingleBean(Tokenizer.class);
              var tokenizer = context.getBean(Tokenizer.class);
              var type = new TokenType("DNI");
              var domain = new TokenDomain("test");
              try (var real = SensitiveValue.of("00123456");
                  var synthetic = SensitiveValue.of("synthetic:document_one")) {
                assertThrows(
                    BusinessException.class,
                    () -> tokenizer.tokenize(type, real, domain, UUID.randomUUID().toString()));
                var first =
                    tokenizer.tokenize(type, synthetic, domain, UUID.randomUUID().toString());
                var second =
                    tokenizer.tokenize(type, synthetic, domain, UUID.randomUUID().toString());
                assertEquals(first.token(), second.token());
                assertTrue(first.created());
                assertFalse(second.created());
                assertEquals(
                    first.token(), tokenizer.lookup(type, synthetic, domain, "test").orElseThrow());
                try (var revealed = tokenizer.detokenize(type, first.token(), domain, "test")) {
                  assertEquals(synthetic.reveal(), revealed.reveal());
                }
                assertTrue(tokenizer.delete(type, first.token(), domain));
                assertFalse(tokenizer.delete(type, first.token(), domain));
              }
            });
  }
}
