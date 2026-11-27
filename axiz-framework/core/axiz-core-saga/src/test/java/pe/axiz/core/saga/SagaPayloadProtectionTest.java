package pe.axiz.core.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import pe.axiz.core.crypto.CryptoException;
import pe.axiz.core.crypto.DataKeyProvider;
import pe.axiz.core.crypto.EnvelopeEncryptor;
import pe.axiz.core.crypto.EnvelopePayloadProtector;
import pe.axiz.core.resilience.Backoff;
import pe.axiz.core.resilience.RetryPolicy;

class SagaPayloadProtectionTest {
  @Test
  void persistedContextAndResumeUseAuthoritativeInstanceBinding() {
    var keys =
        new DataKeyProvider() {
          public DataKey generate(String context) {
            return new DataKey(new byte[32], new byte[] {1});
          }

          public byte[] decrypt(byte[] encrypted, String context) {
            return new byte[32];
          }
        };
    try (var envelope =
        new EnvelopeEncryptor(keys, Duration.ofMinutes(1), 100, Clock.systemUTC())) {
      var protector = new EnvelopePayloadProtector(envelope);
      var store = new InMemorySagaStateStore();
      var retry = RetryPolicy.transientFailures(1, Backoff.fixed(Duration.ZERO));
      var orchestrator =
          new SagaOrchestrator(
              store, retry, retry, SagaListener.none(), Clock.systemUTC(), protector);
      ContextCodec<String> codec =
          new ContextCodec<>() {
            public String encode(String value) {
              return value;
            }

            public String decode(String stored) {
              return stored;
            }
          };
      var step =
          new SagaStep<String>() {
            public String name() {
              return "process";
            }

            public String execute(String context) {
              return context + "!";
            }

            public void compensate(String context) {}
          };
      var definition = new SagaDefinition<>("payment", List.of(step));
      assertTrue(orchestrator.start(definition, codec, "row-1", "00123456").completed());
      var stored = store.find("row-1").orElseThrow();
      assertTrue(stored.context().startsWith("axzp1:"));
      assertFalse(stored.context().contains("00123456"));
      assertEquals("00123456!", orchestrator.resume(definition, codec, "row-1").context());
      store.create(
          SagaState.builder()
              .sagaId("row-2")
              .sagaName(stored.sagaName())
              .status(stored.status())
              .stepIndex(stored.stepIndex())
              .context(stored.context())
              .error(stored.error())
              .updatedAt(stored.updatedAt())
              .version(0)
              .build());
      assertThrows(CryptoException.class, () -> orchestrator.resume(definition, codec, "row-2"));
      var disabled =
          new SagaOrchestrator(store, retry, retry, SagaListener.none(), Clock.systemUTC());
      assertThrows(CryptoException.class, () -> disabled.resume(definition, codec, "row-1"));
    }
  }
}
