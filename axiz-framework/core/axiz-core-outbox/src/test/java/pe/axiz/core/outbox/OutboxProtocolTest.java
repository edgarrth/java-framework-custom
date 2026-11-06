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
import pe.axiz.core.commons.json.JsonReader;
import pe.axiz.core.commons.tx.TransactionRunner;
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
    return new EventEnvelope(
        "event",
        "urn:service",
        "changed.v1",
        NOW,
        "aggregate",
        "application/json",
        new byte[] {'{', '}'},
        Map.of());
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
          new OutboxRecord(
              "event",
              "topic",
              "order",
              "aggregate",
              "changed.v1",
              CloudEventJson.write(event()),
              OutboxRecord.Status.IN_FLIGHT,
              0,
              NOW,
              NOW,
              null,
              7,
              owner,
              expired ? NOW : NOW.plus(lease)));
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
    public List<OutboxRecord> lockDue(int size, Instant now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void markPublished(String id, Instant at) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void markFailed(String id, int attempts, Instant next, String error, boolean giveUp) {
      throw new UnsupportedOperationException();
    }

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
    return new OutboxRelay(
        store,
        publisher,
        store.tx,
        CLOCK,
        10,
        maxAttempts,
        Backoff.fixed(Duration.ofSeconds(3)),
        Duration.ofMinutes(3),
        Duration.ofMillis(1));
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
        relay(
                expired,
                (destination, event) -> new PublishResult(event.id(), destination, null),
                2)
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
