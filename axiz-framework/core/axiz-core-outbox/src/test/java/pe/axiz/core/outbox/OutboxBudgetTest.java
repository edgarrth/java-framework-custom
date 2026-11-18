package pe.axiz.core.outbox;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import pe.axiz.core.concurrent.LeaseTiming;
import pe.axiz.core.messaging.CloudEventJson;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.PublishResult;
import pe.axiz.core.resilience.Backoff;

class OutboxBudgetTest {
  private static final Duration LEASE = Duration.ofMinutes(3);
  private static final Duration BUDGET = Duration.ofSeconds(70);
  private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");

  static final class Store extends OutboxProtocolTest.Store {
    final AtomicLong time;
    final Duration claimDuration;
    final List<String> published = new ArrayList<>();

    Store(AtomicLong time, Duration claimDuration) {
      super(new OutboxProtocolTest.Transactions());
      this.time = time;
      this.claimDuration = claimDuration;
    }

    @Override
    public List<OutboxRecord> claimDue(int size, Duration lease, String owner) {
      if (claimed) return List.of();
      claimed = true;
      time.addAndGet(claimDuration.toNanos());
      return IntStream.range(0, 3)
          .mapToObj(
              i -> {
                String id = "event-" + i;
                var event =
                    EventEnvelope.builder()
                        .id(id)
                        .source("urn:test")
                        .type("changed.v1")
                        .time(NOW)
                        .subject(id)
                        .dataContentType("application/json")
                        .data(new byte[0])
                        .extensions(Map.of())
                        .build();
                return OutboxRecord.builder(
                        id, "topic", event.type(), CloudEventJson.write(event), NOW)
                    .aggregate("order", id)
                    .status(OutboxRecord.Status.IN_FLIGHT)
                    .attempts(0)
                    .nextAttemptAt(NOW)
                    .lastError(null)
                    .aggregateSequence(1)
                    .lease(owner, NOW.minusSeconds(3600))
                    .build();
              })
          .limit(size)
          .toList();
    }

    @Override
    public boolean markPublished(String id, String owner, Instant at) {
      assertEquals(NOW, at);
      published.add(id);
      return true;
    }
  }

  static final class Listener implements OutboxRelayListener {
    Duration claim = Duration.ZERO;
    int exhausted;
    int skipped;

    @Override
    public void onClaim(Duration duration, int rows) {
      if (rows > 0) claim = duration;
    }

    @Override
    public void onBudgetExhausted(int count) {
      exhausted++;
      skipped += count;
    }
  }

  private OutboxRelay relay(Store store, Duration sendDuration, Listener listener) {
    return OutboxRelay.builder(
            store,
            (destination, event) -> {
              assertFalse(store.tx.active);
              store.time.addAndGet(sendDuration.toNanos());
              return new PublishResult(event.id(), destination, null);
            },
            store.tx,
            Clock.fixed(NOW, ZoneOffset.UTC))
        .batchSize(3)
        .maxAttempts(2)
        .backoff(Backoff.fixed(Duration.ZERO))
        .lease(LEASE)
        .publishBudget(BUDGET)
        .listener(listener)
        .ticker(store.time::get)
        .build();
  }

  @Test
  void actualBudgetDeterminesBatchSizeAndAClaimAboveTheAllowanceStillPublishes() {
    var store = new Store(new AtomicLong(), Duration.ofSeconds(3));
    var listener = new Listener();
    assertEquals(2, relay(store, BUDGET, listener).relayOnce().published());
    assertEquals(List.of("event-0", "event-1"), store.published);
    assertEquals(Duration.ofSeconds(3), listener.claim);
    assertEquals(1, listener.exhausted);
    assertEquals(1, listener.skipped);
  }

  @Test
  void shortSendsCanPublishMoreThanTheStartupWorstCaseEstimate() {
    var store = new Store(new AtomicLong(Long.MAX_VALUE - 10), Duration.ofSeconds(3));
    var listener = new Listener();
    assertEquals(3, relay(store, Duration.ofSeconds(30), listener).relayOnce().published());
    assertEquals(Duration.ofSeconds(3), listener.claim);
    assertEquals(0, listener.exhausted);
  }

  @Test
  void exactRemainingBudgetIsAcceptedAndOneNanosecondLessIsRejected() {
    Duration available = LEASE.minus(LeaseTiming.margin(LEASE)).minus(BUDGET);
    var exact = new Store(new AtomicLong(), available);
    assertEquals(1, relay(exact, BUDGET, new Listener()).relayOnce().published());
    var tooLate = new Store(new AtomicLong(), available.plusNanos(1));
    var listener = new Listener();
    assertEquals(0, relay(tooLate, Duration.ZERO, listener).relayOnce().published());
    assertEquals(3, listener.skipped);
  }

  @Test
  void constructorRejectsAnUnusableLeaseOrPublicationBudget() {
    var store = new Store(new AtomicLong(), Duration.ZERO);
    for (Duration invalid : List.of(Duration.ZERO, Duration.ofSeconds(-1), LEASE))
      assertThrows(
          IllegalArgumentException.class,
          () ->
              OutboxRelay.builder(
                      store,
                      (d, e) -> new PublishResult(e.id(), d, null),
                      store.tx,
                      Clock.systemUTC())
                  .batchSize(1)
                  .maxAttempts(1)
                  .backoff(Backoff.fixed(Duration.ZERO))
                  .lease(LEASE)
                  .publishBudget(invalid)
                  .build());
    assertDoesNotThrow(
        () ->
            OutboxRelay.builder(
                    store,
                    (d, e) -> new PublishResult(e.id(), d, null),
                    store.tx,
                    Clock.systemUTC())
                .batchSize(1)
                .maxAttempts(1)
                .backoff(Backoff.fixed(Duration.ZERO))
                .lease(LEASE)
                .publishBudget(LEASE.minus(LeaseTiming.margin(LEASE)))
                .build());
  }
}
