package pe.axiz.core.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import pe.axiz.core.commons.tx.TransactionRunner;
import pe.axiz.core.json.JsonReader;
import pe.axiz.core.messaging.CloudEventJson;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventPublisher;
import pe.axiz.core.messaging.PublishResult;
import pe.axiz.core.resilience.Backoff;

/** Pure orchestration tests; adapters independently prove database fencing and atomicity. */
class OutboxProtocolTest {
  private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  static class Transactions implements TransactionRunner {
    boolean active;

    @Override
    public <T> T inTransaction(Callable<T> work) throws Exception {
      assertFalse(active);
      active = true;
      try {
        return work.call();
      } finally {
        active = false;
      }
    }
  }

  private static EventEnvelope event() {
    return EventEnvelope.builder()
        .id("event")
        .source("urn:service")
        .type("changed.v1")
        .time(NOW)
        .subject("aggregate")
        .dataContentType("application/json")
        .data(new byte[] {'{', '}'})
        .extensions(Map.of())
        .build();
  }

  static class Store implements OutboxStore {
    final Transactions tx;
    final List<OutboxRecord> appended = new ArrayList<>();
    boolean claimed;
    boolean expired;
    boolean accept = true;
    int attempts;
    boolean giveUp;
    Instant next;
    String claimOwner;

    Store(Transactions tx) {
      this.tx = tx;
    }

    @Override
    public void append(OutboxRecord record) {
      appended.add(record);
    }

    @Override
    public List<OutboxRecord> claimDue(int size, Duration lease, String owner) {
      assertTrue(tx.active);
      assertTrue(size > 0);
      assertTrue(lease.isPositive());
      if (claimed) return List.of();
      claimed = true;
      claimOwner = owner;
      return List.of(
          OutboxRecord.builder("event", "topic", "changed.v1", CloudEventJson.write(event()), NOW)
              .aggregate("order", "aggregate")
              .status(OutboxRecord.Status.IN_FLIGHT)
              .attempts(0)
              .nextAttemptAt(NOW)
              .lastError(null)
              .aggregateSequence(7)
              .lease(owner, expired ? NOW : NOW.plus(lease))
              .build());
    }

    @Override
    public boolean markPublished(String id, String owner, Instant at) {
      assertTrue(tx.active);
      assertEquals("event", id);
      assertEquals(claimOwner, owner);
      assertEquals(NOW, at);
      return accept;
    }

    @Override
    public boolean markFailed(
        String id, String owner, int attempts, Instant next, String error, boolean giveUp) {
      assertTrue(tx.active);
      assertEquals("event", id);
      assertEquals(claimOwner, owner);
      assertNotNull(error);
      this.attempts = attempts;
      this.next = next;
      this.giveUp = giveUp;
      return accept;
    }

    @Override
    public boolean retry(String id) {
      assertTrue(tx.active);
      return accept;
    }

    @Override
    public boolean skip(String id, String reason) {
      assertTrue(tx.active);
      return accept;
    }

    @Override
    public long countBlockedAggregates() {
      return 0;
    }

    @Override
    public long countSkipped() {
      return 0;
    }

    @Override
    public void verifySchema() {}

    @Override
    public int purgePublished(Instant before) {
      return 0;
    }

    @Override
    public long countPending() {
      return 0;
    }
  }

  private OutboxRelay relay(Store store, EventPublisher publisher, int maxAttempts) {
    return OutboxRelay.builder(store, publisher, store.tx, CLOCK)
        .batchSize(10)
        .maxAttempts(maxAttempts)
        .backoff(Backoff.fixed(Duration.ofSeconds(3)))
        .lease(Duration.ofMinutes(3))
        .publishBudget(Duration.ofMillis(1))
        .build();
  }

  @Test
  void publicationOccursOutsideTheTransactionsAndPropagatesAuthoritativeSequence() {
    var store = new Store(new Transactions());
    var calls = new ArrayList<EventEnvelope>();
    assertEquals(
        1,
        relay(
                store,
                (destination, event) -> {
                  assertFalse(store.tx.active);
                  assertEquals("topic", destination);
                  calls.add(event);
                  return new PublishResult(event.id(), destination, null);
                },
                2)
            .drain());
    assertEquals("7", calls.getFirst().extension("axizseq").orElseThrow());
    assertEquals(
        Map.of("type", "order", "id", "aggregate"),
        JsonReader.parseObject(calls.getFirst().extension("axizaggregate").orElseThrow()));
  }

  @Test
  void wallClockExpiryIsInformationalAndUnconfirmedClaimsDoNotCountAsPublished() {
    var expired = new Store(new Transactions());
    expired.expired = true;
    assertEquals(
        1,
        relay(expired, (destination, event) -> new PublishResult(event.id(), destination, null), 2)
            .relayOnce()
            .published());
    var lost = new Store(new Transactions());
    lost.accept = false;
    assertEquals(
        0,
        relay(lost, (destination, event) -> new PublishResult(event.id(), destination, null), 2)
            .relayOnce()
            .published());
  }

  @Test
  void brokerFailureSchedulesTheRetryOrStopsAtTheConfiguredAttemptLimit() {
    for (int maximum : List.of(1, 2)) {
      var store = new Store(new Transactions());
      var result =
          relay(
                  store,
                  (d, e) -> {
                    throw new IllegalStateException("broker down");
                  },
                  maximum)
              .relayOnce();
      assertEquals(0, result.published());
      assertEquals(1, store.attempts);
      assertEquals(NOW.plusSeconds(3), store.next);
      assertEquals(maximum == 1, store.giveUp);
      assertEquals(maximum == 1 ? 1 : 0, result.failed());
      assertEquals(maximum == 1 ? 0 : 1, result.retried());
    }
  }

  @Test
  void writerPreservesTraceAndNormalizesIncompleteAggregateIdentity() {
    var store = new Store(new Transactions());
    new OutboxWriter(
            store,
            CLOCK,
            (destination, event, write) ->
                write.accept(event.withExtension("traceparent", "trace")))
        .write("topic", event(), null);
    var record = store.appended.getFirst();
    assertEquals(OutboxRecord.Status.PENDING, record.status());
    assertEquals(0, record.attempts());
    assertEquals(NOW, record.createdAt());
    assertEquals(NOW, record.nextAttemptAt());
    assertEquals("axiz.event", record.aggregateType());
    assertEquals("event", record.aggregateId());
    assertEquals("trace", record.event().extension("traceparent").orElseThrow());
  }

  @Test
  void consecutiveHeadsRespectTheTotalBudgetAndExposeAccurateRunTotals() {
    var limits = new ArrayList<Integer>();
    var store =
        new Store(new Transactions()) {
          int claims;

          @Override
          public List<OutboxRecord> claimDue(int size, Duration lease, String owner) {
            limits.add(size);
            if (claims++ == 3) return List.of();
            claimed = false;
            return super.claimDue(size, lease, owner);
          }
        };
    var result =
        relay(store, (destination, event) -> new PublishResult(event.id(), destination, null), 2)
            .relayOnce();
    assertEquals(3, result.published());
    assertEquals(List.of(10, 9, 8, 7), limits);
    assertEquals(3, result.total());
    assertEquals(10, new OutboxRelay.RunResult(2, 3, 5).total());
  }

  @Test
  void retriesAndTerminalFailuresAlsoConsumeTheBatchLimit() {
    var limits = new ArrayList<Integer>();
    var sent = new ArrayList<String>();
    var store =
        new Store(new Transactions()) {
          int claims;

          @Override
          public List<OutboxRecord> claimDue(int size, Duration lease, String owner) {
            assertTrue(tx.active);
            limits.add(size);
            claimOwner = owner;
            if (claims++ > 1) return List.of();
            return (claims == 1 ? List.of("sent", "retry", "failed") : List.of("last"))
                .stream()
                    .limit(size)
                    .map(
                        id ->
                            OutboxRecord.builder(
                                    id,
                                    "topic",
                                    "changed.v1",
                                    CloudEventJson.write(
                                        EventEnvelope.builder()
                                            .id(id)
                                            .source("urn:service")
                                            .type("changed.v1")
                                            .time(NOW)
                                            .subject(id)
                                            .dataContentType("application/json")
                                            .data(new byte[0])
                                            .extensions(Map.of())
                                            .build()),
                                    NOW)
                                .aggregate("order", id)
                                .status(OutboxRecord.Status.IN_FLIGHT)
                                .attempts(id.equals("failed") ? 1 : 0)
                                .nextAttemptAt(NOW)
                                .lastError(null)
                                .aggregateSequence(1)
                                .lease(owner, NOW.plus(lease))
                                .build())
                    .toList();
          }

          @Override
          public boolean markPublished(String id, String owner, Instant at) {
            assertTrue(tx.active);
            assertEquals(claimOwner, owner);
            return true;
          }

          @Override
          public boolean markFailed(
              String id, String owner, int attempts, Instant next, String error, boolean giveUp) {
            assertTrue(tx.active);
            assertEquals(claimOwner, owner);
            assertEquals(id.equals("failed"), giveUp);
            return true;
          }
        };
    var relay =
        OutboxRelay.builder(
                store,
                (destination, event) -> {
                  sent.add(event.id());
                  if (List.of("retry", "failed").contains(event.id()))
                    throw new IllegalStateException("broker rejected publication");
                  return new PublishResult(event.id(), destination, null);
                },
                store.tx,
                CLOCK)
            .batchSize(4)
            .maxAttempts(2)
            .backoff(Backoff.fixed(Duration.ZERO))
            .lease(Duration.ofMinutes(3))
            .publishBudget(Duration.ofMillis(1))
            .build();
    assertEquals(new OutboxRelay.RunResult(2, 1, 1), relay.relayOnce());
    assertEquals(List.of(4, 1), limits);
    assertEquals(List.of("sent", "retry", "failed", "last"), sent);
  }

  @Test
  void drainingMultipleSingleEntryBatchesContinuesUntilNoHeadCanBePublished() {
    var limits = new ArrayList<Integer>();
    var store =
        new Store(new Transactions()) {
          int claims;

          @Override
          public List<OutboxRecord> claimDue(int size, Duration lease, String owner) {
            limits.add(size);
            if (claims++ == 3) return List.of();
            claimed = false;
            return super.claimDue(size, lease, owner);
          }
        };
    var relay =
        OutboxRelay.builder(
                store,
                (destination, event) -> new PublishResult(event.id(), destination, null),
                store.tx,
                CLOCK)
            .batchSize(1)
            .maxAttempts(1)
            .backoff(Backoff.fixed(Duration.ZERO))
            .lease(Duration.ofMinutes(3))
            .publishBudget(Duration.ofMillis(1))
            .build();
    assertEquals(3, relay.drain());
    assertEquals(List.of(1, 1, 1, 1), limits);
  }

  @Test
  void anUnconfirmedFailureDoesNotConsumeBudgetAndTerminalRowsCannotBeRetried() {
    var store = new Store(new Transactions());
    store.accept = false;
    var result =
        relay(
                store,
                (destination, event) -> {
                  throw new IllegalStateException("broker down after losing claim");
                },
                1)
            .relayOnce();
    assertEquals(0, result.total());
    var audit = new ArrayList<String>();
    var admin =
        new OutboxAdmin(store, store.tx, (action, id, reason, at) -> audit.add(reason), CLOCK);
    assertFalse(admin.retry("event"));
    assertTrue(audit.isEmpty());
    store.accept = true;
    String reason = "x".repeat(1000);
    assertTrue(admin.skip("event", reason));
    assertEquals(List.of(reason), audit);
  }

  @Test
  void recoveryRequiresAReasonAndRecordsOnlyActualTransitionsInsideTheTransaction() {
    var store = new Store(new Transactions());
    var evidence = new ArrayList<String>();
    var admin =
        new OutboxAdmin(
            store,
            store.tx,
            (action, id, reason, at) -> {
              assertTrue(store.tx.active);
              assertEquals(NOW, at);
              evidence.add(action + ":" + id + ":" + reason);
            },
            CLOCK);
    assertThrows(IllegalArgumentException.class, () -> admin.skip("event", " "));
    assertThrows(IllegalArgumentException.class, () -> admin.skip("event", "x".repeat(1001)));
    assertTrue(admin.retry("event"));
    assertTrue(admin.skip("event", "approved"));
    assertEquals(List.of("retry:event:explicit-retry", "skip:event:approved"), evidence);
    store.accept = false;
    assertFalse(admin.skip("event", "terminal"));
    assertEquals(2, evidence.size());
  }
}
