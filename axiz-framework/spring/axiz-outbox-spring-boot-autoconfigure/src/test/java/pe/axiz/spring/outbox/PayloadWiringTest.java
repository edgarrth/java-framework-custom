package pe.axiz.spring.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.core.crypto.AsyncPayloadProtector;
import pe.axiz.core.crypto.CryptoException;
import pe.axiz.core.crypto.DataKeyProvider;
import pe.axiz.core.crypto.EnvelopeEncryptor;
import pe.axiz.core.crypto.EnvelopePayloadProtector;
import pe.axiz.core.crypto.PayloadProtector;

class PayloadWiringTest {
  private final ApplicationContextRunner context =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizOutboxPayloadAutoConfiguration.class));

  @Test
  void defaultsNeedNoKeyProviderAndEnvelopeFailsClosedWithMissingOrWrongStackKeys() {
    context.run(c -> assertThat(c.getBean(PayloadProtector.class)).isSameAs(PayloadProtector.NONE));
    context
        .withPropertyValues("axiz.outbox.store=r2dbc")
        .run(
            c -> {
              assertThat(c.getBean(AsyncPayloadProtector.class))
                  .isSameAs(AsyncPayloadProtector.NONE);
              assertThat(c.getBean(PayloadProtector.class)).isSameAs(PayloadProtector.NONE);
            });
    context
        .withPropertyValues("axiz.outbox.payload-protection=envelope")
        .run(c -> assertThat(c).hasFailed());
    var keys =
        new DataKeyProvider() {
          public DataKey generate(String context) {
            return new DataKey(new byte[32], new byte[] {1});
          }

          public byte[] decrypt(byte[] key, String context) {
            return new byte[32];
          }
        };
    context
        .withPropertyValues("axiz.outbox.payload-protection=envelope")
        .withBean(
            EnvelopeEncryptor.class,
            () -> new EnvelopeEncryptor(keys, Duration.ofMinutes(1), 100, Clock.systemUTC()))
        .run(
            c ->
                assertThat(c.getBean(PayloadProtector.class))
                    .isInstanceOf(EnvelopePayloadProtector.class));
    context
        .withPropertyValues("axiz.outbox.store=r2dbc", "axiz.outbox.payload-protection=envelope")
        .withBean(
            EnvelopeEncryptor.class,
            () -> new EnvelopeEncryptor(keys, Duration.ofMinutes(1), 100, Clock.systemUTC()))
        .run(c -> assertThat(c).hasFailed());
    context
        .withPropertyValues("axiz.outbox.payload-read-timeout=0")
        .run(c -> assertThat(c).hasFailed());
  }

  @Test
  void relayReadsOnlyOnItsVirtualWorkerAndCancelsTimedOutNativeRequests() throws Exception {
    var pending = new CompletableFuture<byte[]>();
    var delegate =
        new AsyncPayloadProtector() {
          public CompletionStage<byte[]> protect(byte[] input, String context, String binding) {
            throw new AssertionError("No writes through relay reader");
          }

          public CompletionStage<byte[]> reveal(byte[] input, String context, String binding) {
            return pending;
          }
        };
    var reader = new RelayPayloadReader(delegate, Duration.ofMillis(20));
    assertThrows(
        IllegalStateException.class, () -> reader.reveal(new byte[] {1}, "context", "row"));
    assertThrows(
        UnsupportedOperationException.class,
        () -> reader.protect(new byte[] {1}, "context", "row"));
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor
          .submit(
              () ->
                  assertThrows(
                      CryptoException.class, () -> reader.reveal(new byte[] {1}, "context", "row")))
          .get(5, TimeUnit.SECONDS);
      assertTrue(pending.isCancelled());
      var identity = new RelayPayloadReader(AsyncPayloadProtector.NONE, Duration.ofSeconds(1));
      assertArrayEquals(
          new byte[] {7},
          executor
              .submit(() -> identity.reveal(new byte[] {7}, "context", "row"))
              .get(5, TimeUnit.SECONDS));
    }
  }
}
