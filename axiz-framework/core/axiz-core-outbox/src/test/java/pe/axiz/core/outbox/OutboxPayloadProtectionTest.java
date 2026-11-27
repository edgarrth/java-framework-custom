package pe.axiz.core.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.axiz.core.crypto.CryptoException;
import pe.axiz.core.crypto.DataKeyProvider;
import pe.axiz.core.crypto.EnvelopeEncryptor;
import pe.axiz.core.crypto.EnvelopePayloadProtector;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.PublishResult;
import pe.axiz.core.resilience.Backoff;

class OutboxPayloadProtectionTest {
  @Test
  void writerStoresOnlyCiphertextAndRelayRevealsForBrokerWithRowBinding() {
    var tx = new OutboxProtocolTest.Transactions();
    var store =
        new OutboxProtocolTest.Store(tx) {
          @Override
          public List<OutboxRecord> claimDue(int size, Duration lease, String owner) {
            assertTrue(tx.active);
            if (claimed) return List.of();
            claimed = true;
            claimOwner = owner;
            return List.copyOf(appended);
          }

          @Override
          public boolean markPublished(String id, String owner, Instant at) {
            assertTrue(tx.active);
            return true;
          }
        };
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
      var writer = new OutboxWriter(store, Clock.systemUTC(), OutboxWriteTracing.NONE, protector);
      var event =
          EventEnvelope.builder()
              .id("event")
              .source("urn:test")
              .type("changed.v1")
              .time(Instant.now())
              .subject("aggregate")
              .dataContentType("application/json")
              .data("{\"document\":\"00123456\"}".getBytes(StandardCharsets.UTF_8))
              .extensions(Map.of())
              .build();
      writer.write("topic", event, "account");
      var stored = store.appended.getFirst();
      assertTrue(stored.envelopeJson().startsWith("axzp1:"));
      assertFalse(stored.envelopeJson().contains("00123456"));
      assertFalse(stored.toString().contains("00123456"));
      assertThrows(CryptoException.class, stored::event);
      var swapped =
          OutboxRecord.builder(
                  "another-row",
                  stored.destination(),
                  stored.eventType(),
                  stored.envelopeJson(),
                  stored.createdAt())
              .aggregate(stored.aggregateType(), stored.aggregateId())
              .status(stored.status())
              .attempts(stored.attempts())
              .nextAttemptAt(stored.nextAttemptAt())
              .lastError(null)
              .aggregateSequence(0)
              .lease(null, null)
              .build();
      assertThrows(CryptoException.class, () -> swapped.event(protector));
      var sent = new ArrayList<EventEnvelope>();
      var relay =
          OutboxRelay.builder(
                  store,
                  (destination, outgoing) -> {
                    sent.add(outgoing);
                    return new PublishResult(outgoing.id(), destination, null);
                  },
                  tx,
                  Clock.systemUTC())
              .batchSize(1)
              .maxAttempts(1)
              .backoff(Backoff.fixed(Duration.ZERO))
              .lease(Duration.ofSeconds(5))
              .publishBudget(Duration.ofSeconds(1))
              .listener(OutboxRelayListener.NOOP)
              .protector(protector)
              .build();
      assertEquals(1, relay.relayOnce().published());
      assertEquals(
          "{\"document\":\"00123456\"}",
          new String(sent.getFirst().data(), StandardCharsets.UTF_8));
    }
  }
}
