package pe.axiz.spring.tokenization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.tokenization.AsyncTokenizer;
import pe.axiz.core.tokenization.SensitiveValue;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenType;
import pe.axiz.core.tokenization.TokenizerCapabilities;

class SyntheticTokenLifecycleTest {
  private static final TokenType TYPE = new TokenType("DNI");
  private static final TokenDomain DOMAIN = new TokenDomain("test");

  @Test
  void reactiveAndAsyncConsumersShareTheSameLifecycleAndRespectDomainBoundaries() {
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizTokenizationLocalAutoConfiguration.class))
        .withPropertyValues("axiz.tokenization.mode=local", "axiz.config.environment=test")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var reactive = context.getBean(ReactiveTokenizer.class);
              var async = context.getBean(AsyncTokenizer.class);
              assertThat(reactive.capabilities()).isEqualTo(TokenizerCapabilities.vault());
              assertThat(async.capabilities()).isEqualTo(reactive.capabilities());
              try (var value = SensitiveValue.of("synthetic:identity")) {
                var pending = reactive.tokenize(TYPE, value, DOMAIN, UUID.randomUUID().toString());
                assertThat(reactive.lookup(TYPE, value, DOMAIN, "support").block()).isEmpty();
                var created = pending.block();
                assertThat(created.created()).isTrue();
                var existing =
                    async
                        .tokenize(TYPE, value, DOMAIN, UUID.randomUUID().toString())
                        .toCompletableFuture()
                        .join();
                assertThat(existing.created()).isFalse();
                assertThat(existing.token()).isEqualTo(created.token());
                assertThat(
                        async.lookup(TYPE, value, DOMAIN, "support").toCompletableFuture().join())
                    .contains(created.token());
                assertThat(reactive.describe(TYPE, created.token(), DOMAIN).block()).isPresent();
                assertThat(
                        async.describe(TYPE, created.token(), DOMAIN).toCompletableFuture().join())
                    .isPresent();
                assertThat(
                        reactive.describe(new TokenType("OTHER"), created.token(), DOMAIN).block())
                    .isEmpty();
                assertThat(
                        reactive.describe(TYPE, created.token(), new TokenDomain("other")).block())
                    .isEmpty();
                try (var revealed =
                        reactive.detokenize(TYPE, created.token(), DOMAIN, "support").block();
                    var other =
                        async
                            .detokenize(TYPE, created.token(), DOMAIN, "support")
                            .toCompletableFuture()
                            .join()) {
                  assertThat(revealed.reveal()).isEqualTo(value.reveal());
                  assertThat(other.reveal()).isEqualTo(value.reveal());
                }
                assertThat(async.delete(TYPE, created.token(), DOMAIN).toCompletableFuture().join())
                    .isTrue();
                assertThat(reactive.delete(TYPE, created.token(), DOMAIN).block()).isFalse();
                assertThrows(
                    BusinessException.class,
                    () -> reactive.detokenize(TYPE, created.token(), DOMAIN, "support").block());
              }
            });
  }

  @Test
  void syntheticModeRejectsInvalidCorrelationAndPurposeAndCannotRunAfterClose() {
    var tokenizer = new SyntheticTokenizer();
    try (tokenizer;
        var value = SensitiveValue.of("synthetic:identity")) {
      for (String id :
          new String[] {"invalid", "1-1-1-1-1", "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"})
        assertThrows(BusinessException.class, () -> tokenizer.tokenize(TYPE, value, DOMAIN, id));
      assertThrows(
          BusinessException.class, () -> tokenizer.lookup(TYPE, value, DOMAIN, "contains space"));
      tokenizer.close();
      assertThrows(BusinessException.class, () -> tokenizer.lookup(TYPE, value, DOMAIN, "support"));
    }
  }
}
