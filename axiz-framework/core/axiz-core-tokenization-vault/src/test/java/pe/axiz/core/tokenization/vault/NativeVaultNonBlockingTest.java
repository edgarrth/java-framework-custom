package pe.axiz.core.tokenization.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.blockhound.BlockHound;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

class NativeVaultNonBlockingTest {
  @BeforeAll
  static void instrument() {
    BlockHound.install();
  }

  @Test
  void tokenizeAndRevealOnEventLoopWithoutBlockingFacadeOrExecutor() {
    var fixture = new AsyncVaultTokenizerTest();
    try {
      StepVerifier.create(
              Flux.range(0, 200)
                  .delayElements(Duration.ofMillis(2))
                  .concatMap(
                      i ->
                          Mono.fromCompletionStage(
                              fixture.tokenize("0088%04d".formatted(i), "one")))
                  .flatMap(
                      result ->
                          Mono.fromCompletionStage(
                              fixture.engine.detokenize(
                                  VaultTokenizerTest.caller("one"),
                                  VaultTokenizerTest.DNI,
                                  result.token(),
                                  VaultTokenizerTest.DOMAIN,
                                  "customer-support")))
                  .subscribeOn(Schedulers.parallel()))
          .assertNext(
              value -> {
                try (value) {
                  assertEquals(8, value.reveal().length());
                }
              })
          .thenConsumeWhile(
              value -> {
                try (value) {
                  return value.reveal().length() == 8;
                }
              })
          .expectComplete()
          .verify(Duration.ofSeconds(5));
      assertEquals(400, fixture.released.get());
    } finally {
      fixture.close();
    }
  }
}
